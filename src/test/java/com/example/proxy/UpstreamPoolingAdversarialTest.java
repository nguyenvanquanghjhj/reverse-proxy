package com.example.proxy;

import com.example.proxy.config.ConfigLoader;
import com.example.proxy.handler.UpstreamTransport;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Framing and no-body responses must not poison a reused upstream connection. */
public final class UpstreamPoolingAdversarialTest {
    private static void check(boolean yes,String message){if(!yes)throw new AssertionError(message);}
    public static void main(String[] ignored)throws Exception {
        var ss=new ServerSocket(0,32,InetAddress.getLoopbackAddress());
        var accepted=new AtomicInteger();
        var server=Thread.ofVirtual().start(()->{
            while(!ss.isClosed()){
                try {
                    var socket=ss.accept();accepted.incrementAndGet();
                    Thread.ofVirtual().start(()->serve(socket));
                } catch(IOException closed) {break;}
            }
        });
        var props=new Properties();props.setProperty("backend.servers","127.0.0.1:"+ss.getLocalPort()+":8");
        props.setProperty("proxy.connection-pool.enabled","true");
        props.setProperty("proxy.connection-pool.max-idle-per-backend","2");
        try(var t=new UpstreamTransport(ConfigLoader.load(props))){
            int port=ss.getLocalPort();
            for(int i=0;i<2;i++){
                var response=t.exchange("127.0.0.1",port,"GET",URI.create("/chunked"),Map.of(),new byte[0]);
                check(response.status()==200,"chunk status");
                check(Arrays.equals(response.body(),"PBL4".getBytes(StandardCharsets.US_ASCII)),"chunk content");
            }
            check(accepted.get()==1,"chunked complete framing should permit reuse");
            for(int i=0;i<2;i++){
                var response=t.exchange("127.0.0.1",port,"HEAD",URI.create("/head"),Map.of(),new byte[0]);
                check(response.status()==200&&response.body().length==0,"HEAD body forbidden");
            }
            check(accepted.get()==3,"HEAD sockets must not be reused");
            int afterFirst204 = -1;
            for(int i=0;i<2;i++){
                var response=t.exchange("127.0.0.1",port,"GET",URI.create("/nocontent"),Map.of(),new byte[0]);
                check(response.status()==204&&response.body().length==0,"204 body forbidden");
                if (i == 0) afterFirst204 = accepted.get();
            }
            // The first 204 may borrow the idle chunked GET socket; its response must close it.
            check(afterFirst204 == 3, "first 204 can borrow the prior safe GET socket");
            check(accepted.get()==afterFirst204+1,"204 socket must not be reused");
            int afterFirst304 = -1;
            for(int i=0;i<2;i++){
                var response=t.exchange("127.0.0.1",port,"GET",URI.create("/notmodified"),Map.of(),new byte[0]);
                check(response.status()==304&&response.body().length==0,"304 body forbidden");
                if (i == 0) afterFirst304 = accepted.get();
            }
            check(accepted.get()==afterFirst304+1,"304 socket must not be reused");
            int afterFirstClose = -1;
            for(int i=0;i<2;i++){
                var response=t.exchange("127.0.0.1",port,"GET",URI.create("/close"),Map.of(),new byte[0]);
                check(response.status()==200,"close response");
                if (i == 0) afterFirstClose = accepted.get();
            }
            check(accepted.get()==afterFirstClose+1,"Connection: close must not be reused");
        } finally {ss.close();server.join(1000);}
        System.out.println("UpstreamPoolingAdversarialTest PASS");
    }
    private static void serve(Socket s){
        try(s){
            s.setSoTimeout(3000);
            InputStream in=s.getInputStream();OutputStream out=s.getOutputStream();
            while(true){
                var bytes=new ByteArrayOutputStream();int ch;
                while((ch=in.read())!=-1){
                    bytes.write(ch);if(bytes.size()>8192)return;
                    if(bytes.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n"))break;
                }
                if(ch<0)return;
                var req=bytes.toString(StandardCharsets.US_ASCII);
                int length=0;
                for(String line:req.split("\r\n"))if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))
                    length=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());
                while(length-->0)if(in.read()<0)return;
                String path=req.split(" ")[1];
                String response=switch(path){
                    case "/chunked" -> "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: keep-alive\r\n\r\n4\r\nPBL4\r\n0\r\n\r\n";
                    case "/head" -> "HTTP/1.1 200 OK\r\nContent-Length: 7\r\nConnection: keep-alive\r\n\r\n";
                    case "/nocontent" -> "HTTP/1.1 204 No Content\r\nConnection: keep-alive\r\n\r\n";
                    case "/notmodified" -> "HTTP/1.1 304 Not Modified\r\nConnection: keep-alive\r\n\r\n";
                    default -> "HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\nPBL4";
                };
                out.write(response.getBytes(StandardCharsets.US_ASCII));out.flush();
                if(path.equals("/close"))return;
            }
        }catch(IOException ignored){}
    }
}
