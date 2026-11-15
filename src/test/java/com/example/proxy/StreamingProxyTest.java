package com.example.proxy;

import com.example.proxy.handler.StreamingProxy;
import com.example.proxy.backend.BackendServer;
import com.example.proxy.config.ConfigLoader;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.security.*;
import java.util.concurrent.TimeUnit;

/** True HTTP/1.1 fixture; tests response bytes, partial content and downstream framing.
 * Large file backend filesystem tests run separately in tests/run_standalone.py.
 */
public final class StreamingProxyTest {
    static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
    public static void main(String[] args)throws Exception {
        byte[] data=new byte[8*1024*1024];new Random(1234).nextBytes(data);
        HttpServer backend=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        backend.createContext("/",ex->{try(ex){
            ex.getResponseHeaders().set("Accept-Ranges","bytes");
            String range=ex.getRequestHeaders().getFirst("Range");
            boolean partial="bytes=1024-4095".equals(range);
            if(partial)ex.getResponseHeaders().set("Content-Range","bytes 1024-4095/"+data.length);
            ex.getResponseHeaders().set("Content-Type","application/octet-stream");
            if(ex.getRequestMethod().equals("HEAD")){ex.getResponseHeaders().set("Content-Length",String.valueOf(data.length));ex.sendResponseHeaders(200,-1);}
            else if(partial){ex.sendResponseHeaders(206,3072);ex.getResponseBody().write(data,1024,3072);}
            else {ex.sendResponseHeaders(200,data.length);ex.getResponseBody().write(data);}
        }});
        backend.start();
        var properties=new Properties();properties.setProperty("backend.servers","127.0.0.1:"+backend.getAddress().getPort()+":8");
        properties.setProperty("proxy.request.timeout.ms","15000");
        var streaming=new StreamingProxy(ConfigLoader.load(properties));
        var server=new HttpServer[1];
        server[0]=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server[0].createContext("/",ex->{try(ex){try{
            streaming.transfer(ex,new BackendServer("127.0.0.1",backend.getAddress().getPort(),8),new HashMap<>(ex.getRequestHeaders()),System.nanoTime()+TimeUnit.SECONDS.toNanos(15));
        }catch(IOException failure){if(ex.getResponseCode()==-1)ex.sendResponseHeaders(502,-1);}}});
        server[0].start();
        try {
            String url="http://127.0.0.1:"+server[0].getAddress().getPort()+"/api/pbl4/files/sample.bin";
            var client=HttpClient.newHttpClient();
            var full=client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
            check(full.statusCode()==200,"full status");check(Arrays.equals(data,full.body()),"full bytes");
            check(full.headers().allValues("Via").contains("1.1 pbl4"),"streaming response identifies intermediary");
            var part=client.send(HttpRequest.newBuilder(URI.create(url)).header("Range","bytes=1024-4095").GET().build(),HttpResponse.BodyHandlers.ofByteArray());
            check(part.statusCode()==206,"partial status");check(Arrays.equals(Arrays.copyOfRange(data,1024,4096),part.body()),"range data");
            check(part.headers().firstValue("content-range").orElse("").equals("bytes 1024-4095/"+data.length),"range header");
            var head=client.send(HttpRequest.newBuilder(URI.create(url)).method("HEAD",HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofByteArray());
            check(head.statusCode()==200&&head.body().length==0,"HEAD");
            check(head.headers().firstValue("Content-Length").orElse("").equals(Integer.toString(data.length)),"HEAD representation length preserved");
            check(StreamingProxy.eligible("GET","/api/pbl4/files/a.pdf"),"eligible GET");
            check(!StreamingProxy.eligible("POST","/api/store/orders"),"business writes never streamed");
            System.out.println("StreamingProxyTest PASS");
        } finally{server[0].stop(0);backend.stop(0);streaming.close();}
        protocolFaults();
    }
    private static void protocolFaults()throws Exception {
        // All successful file responses are fixed-length. The existing buffered route
        // retains chunked/EOF compatibility; this route rejects unknown-length files.
        raw("HTTP/1.1 103 Early Hints\r\nLink: </a>\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nabc",200,false,false);
        raw("HTTP/1.1 204 No Content\r\n\r\n",204,false,false);
        raw("HTTP/1.1 304 Not Modified\r\nETag: \"a\"\r\n\r\n",304,false,false);
        raw("HTTP/1.1 200 OK\r\nContent-Length: 3\r\nContent-Length: 4\r\n\r\nabc",502,false,false);
        raw("HTTP/1.1 206 Partial Content\r\nContent-Length: 3\r\n\r\nabc",502,false,false);
        raw("HTTP/1.1 206 Partial Content\r\nContent-Length: 3\r\nContent-Range: bytes 0-4/5\r\n\r\nabc",502,false,false);
        raw("HTTP/1.1 206 Partial Content\r\nContent-Length: 3\r\nContent-Range: bytes 0-2/3\r\n\r\nabc",206,false,false);
        raw("HTTP/1.1 200 OK\r\nContent-Length: 3\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\n\r\n",502,false,false);
        raw("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\n\r\n",502,false,false);
        raw("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\nabc",502,false,false);
        raw("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nabc",200,true,false);
        raw("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nabc",200,true,true);
        System.out.println("Streaming framing/informational/partial/deadline checks PASS");
    }
    private static void raw(String response,int expected,boolean incomplete,boolean stall)throws Exception {
        try(var listener=new ServerSocket(0,8,InetAddress.getLoopbackAddress())) {
            Thread responder=Thread.ofVirtual().start(()->{
                try(var socket=listener.accept()) {
                    socket.setSoTimeout(3000);
                    int state=0;while(state<4){int b=socket.getInputStream().read();if(b<0)return;state=b=="\r\n\r\n".charAt(state)?state+1:b=='\r'?1:0;}
                    socket.getOutputStream().write(response.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));socket.getOutputStream().flush();
                    if(stall)Thread.sleep(3000);
                }catch(Exception ignored){}
            });
            var p=new Properties();p.setProperty("backend.servers","127.0.0.1:"+listener.getLocalPort()+":4");
            try(var relay=new StreamingProxy(ConfigLoader.load(p))) {
                var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
                var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(executor);
                server.createContext("/",ex->{try(ex){try{
                    relay.transfer(ex,new BackendServer("127.0.0.1",listener.getLocalPort(),4),Map.of(),System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(stall?300:3000));
                }catch(IOException failure){if(ex.getResponseCode()==-1)ex.sendResponseHeaders(502,-1);}}});
                server.start();
                try(var client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
                    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/api/pbl4/files/raw.bin")).timeout(java.time.Duration.ofSeconds(5)).build();
                    var received=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
                    check(received.statusCode()==expected,"raw status "+expected+" got "+received.statusCode());
                    boolean broken=false;
                    try(var input=received.body()){input.readAllBytes();}catch(IOException failure){broken=true;}
                    check(broken==incomplete,"incomplete body must fail at client");
                    long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                    while(!relay.metricsJson().contains("\"active\":0")&&System.nanoTime()<until)Thread.sleep(10);
                    check(relay.metricsJson().contains("\"active\":0")&&relay.metricsJson().contains("\"openBodies\":0"),"all stream resources released");
                    if(stall)check(relay.metricsJson().contains("\"timeouts\":1"),"stall counts as bounded timeout");
                }finally{server.stop(0);executor.shutdownNow();}
            }finally{responder.interrupt();responder.join(1000);}
        }
    }
}
