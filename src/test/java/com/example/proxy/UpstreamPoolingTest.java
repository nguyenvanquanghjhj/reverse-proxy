package com.example.proxy;

import com.example.proxy.config.ConfigLoader;
import com.example.proxy.handler.UpstreamTransport;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Two sequential GETs must share one live HTTP/1.1 socket when pooling is enabled.
 * POST is never automatically replayed or inserted into the reusable pool.
 */
public final class UpstreamPoolingTest {
    private static void require(boolean b,String what){if(!b)throw new AssertionError(what);}
    public static void main(String[] ignored)throws Exception {
        var server=new ServerSocket(0, 32, InetAddress.getLoopbackAddress());
        var accepts=new AtomicInteger();
        var serving=Thread.ofVirtual().start(()->{
            while(!server.isClosed()) {
                try {
                    var socket=server.accept();accepts.incrementAndGet();
                    Thread.ofVirtual().start(()->handle(socket));
                } catch(IOException closed) { break; }
            }
        });
        var props=new Properties();
        props.setProperty("backend.servers","127.0.0.1:"+server.getLocalPort()+":8");
        props.setProperty("proxy.connection-pool.enabled","true");
        props.setProperty("proxy.connection-pool.max-idle-per-backend","2");
        var config=ConfigLoader.load(props);
        try(var transport=new UpstreamTransport(config)) {
            for(int i=0;i<2;i++) {
                var response=transport.exchange("127.0.0.1",server.getLocalPort(),"GET",
                        URI.create("/item"),Map.of(),new byte[0]);
                require(response.status()==200&&new String(response.body(),StandardCharsets.US_ASCII).equals("PBL4"),"GET payload");
            }
            require(accepts.get()==1,"safe GETs should reuse the same upstream socket: "+accepts.get());
            var response=transport.exchange("127.0.0.1",server.getLocalPort(),"POST",
                    URI.create("/write"),Map.of(),"x".getBytes(StandardCharsets.US_ASCII));
            require(response.status()==200,"POST remains supported");
            require(accepts.get()==2,"POST must not reuse pooled socket");
            require(transport.connectionPoolJson().contains("\"hits\":1"),"pool hit metrics");
        } finally {server.close();serving.join(1000);}
        System.out.println("UpstreamPoolingTest PASS");
    }
    private static void handle(Socket s) {
        try(s) {
            s.setSoTimeout(2500);
            InputStream input=s.getInputStream();OutputStream output=s.getOutputStream();
            while(!s.isClosed()) {
                ByteArrayOutputStream header=new ByteArrayOutputStream();
                int current=-1;
                while((current=input.read())!=-1) {
                    header.write(current);
                    if(header.size()>8192)return;
                    String tail=header.toString(StandardCharsets.US_ASCII);
                    if(tail.endsWith("\r\n\r\n"))break;
                }
                if(current<0)return;
                String request=header.toString(StandardCharsets.US_ASCII);
                int bodyBytes=0;
                for(String line:request.split("\r\n")) if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))
                    bodyBytes=Integer.parseInt(line.split(":",2)[1].trim());
                while(bodyBytes-->0)if(input.read()<0)return;
                boolean close=request.toLowerCase(Locale.ROOT).contains("connection: close");
                output.write(("HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: "+(close?"close":"keep-alive")+"\r\n\r\nPBL4").getBytes(StandardCharsets.US_ASCII));
                output.flush();
                if(close)return;
            }
        } catch(IOException ignored) { }
    }
}
