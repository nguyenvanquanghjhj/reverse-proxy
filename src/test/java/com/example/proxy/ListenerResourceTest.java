package com.example.proxy;

import com.example.proxy.server.BoundedRequestExecutor;
import com.example.proxy.server.HttpListenerPolicy;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Forks fresh JVMs because JDK HttpServer settings latch at first listener creation. */
public final class ListenerResourceTest {
    public static void main(String[] args)throws Exception {
        if(args.length==0){
            for(String mode:List.of("baseline","bounded")){
                var child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-ea","-cp",System.getProperty("java.class.path"),ListenerResourceTest.class.getName(),mode).redirectErrorStream(true).start();
                if(!child.waitFor(15,TimeUnit.SECONDS)){child.destroyForcibly();throw new AssertionError("Listener child timeout");}
                String output=new String(child.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
                if(child.exitValue()!=0)throw new AssertionError(output);System.out.print(output);
            }
            System.out.println("ListenerResourceTest PASS: accepted sockets, incomplete headers, recovery in fresh JVMs");return;
        }
        boolean bounded=args[0].equals("bounded");
        System.setProperty("jdk.httpserver.maxConnections",bounded?"8":"-1");
        if(bounded)HttpListenerPolicy.installDefaults();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),32);
        var executor=new BoundedRequestExecutor(4,300);server.setExecutor(executor);
        server.createContext("/",e->{try(e){e.getRequestBody().readAllBytes();e.sendResponseHeaders(200,2);e.getResponseBody().write(new byte[]{79,75});}});server.start();
        var sockets=new ArrayList<Socket>();int alive=0;
        try {
            for(int i=0;i<24;i++){var s=new Socket("127.0.0.1",server.getAddress().getPort());s.setSoTimeout(30);sockets.add(s);}
            Thread.sleep(150);
            for(var s:sockets)try{if(s.getInputStream().read()>=0)throw new AssertionError("Unexpected response without request");}catch(SocketTimeoutException open){alive++;}catch(SocketException rejected){}
            if(alive!=(bounded?8:24))throw new AssertionError("Alive zero-byte connections="+alive+" mode="+args[0]);
            System.out.println(args[0]+": live zero-byte sockets="+alive+" / 24; application executor limit=4");
            for(var s:sockets)s.close();sockets.clear();Thread.sleep(100);
            for(int i=0;i<4;i++){var s=new Socket("127.0.0.1",server.getAddress().getPort());s.setSoTimeout(2000);s.getOutputStream().write("GET / HTTP/1.1\r\nHost:".getBytes(StandardCharsets.US_ASCII));sockets.add(s);}
            Thread.sleep(800);
            for(var s:sockets)try{if(s.getInputStream().read()!=-1)throw new AssertionError("Incomplete header survived deadline");}catch(SocketException cancelled){}
            for(var s:sockets)s.close();sockets.clear();Thread.sleep(100);
            try(var s=new Socket("127.0.0.1",server.getAddress().getPort())){
                s.setSoTimeout(2000);s.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                if(!new String(s.getInputStream().readAllBytes(),StandardCharsets.US_ASCII).endsWith("OK"))throw new AssertionError("Listener did not recover");
            }
        } finally {for(var s:sockets)s.close();server.stop(0);executor.close();}
    }
}
