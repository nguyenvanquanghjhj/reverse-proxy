package com.example.proxy;

import com.example.proxy.backend.BackendServer;
import com.example.proxy.config.ProxyConfig;
import com.example.proxy.handler.ProxyHandler;
import com.example.proxy.loadbalancer.Dispatcher;
import com.example.proxy.metrics.TrafficMetrics;
import com.example.shop.JsonCodec;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.util.*;

/** Regression: a completed upstream response is not a completed client response. */
public final class TrafficOutcomeTest {
    public static void main(String[] args)throws Exception {
        for(var outcome:TrafficMetrics.Outcome.values()) {
            var m=new TrafficMetrics();m.started("OTHER");
            int status=switch(outcome){case SUCCESS->200;case BUSINESS_REFUSAL->409;case SERVICE_PAUSED->423;case FILE_MISSING->404;case BACKEND_REJECTED->429;case TIMEOUT->504;default->503;};
            m.completed("OTHER",status,false,true,1,outcome);
            var t=total(m.json());check(number(t,"completed")==1,"each outcome completes once");
            check(number(map(t.get("outcomes")),outcome.name())==1,"outcome exported: "+outcome);
        }
        var m=new TrafficMetrics();
        m.started("OTHER");m.completed("OTHER",200,true,true,1,TrafficMetrics.Outcome.SUCCESS);
        m.started("OTHER");m.completed("OTHER",-1,true,false,2);
        m.started("OTHER");m.completed("OTHER",422,false,true,3);
        var total=total(m.json());check(number(total,"started")==number(total,"completed")+number(total,"active"),"started invariant");
        check(number(total,"completed")==number(total,"successful")+number(total,"failed"),"completion invariant");
        check(number(total,"noResponse")==1&&number(total,"ioFailures")==2&&number(total,"responseInterrupted")==1,"overlapping I/O diagnostics retain scope");
        check(number(total,"http4xx")==1&&number(total,"http5xx")==0,"status independent from I/O");
        check(number(map(total.get("outcomes")),"CLIENT_FAILURE")==2,"provisional success replaced");
        check(TrafficMetrics.classify("OTHER",503,false,true,"DATABASE_UNAVAILABLE")==TrafficMetrics.Outcome.DATABASE_UNAVAILABLE,"DB classification");
        check(TrafficMetrics.classify("OTHER",423,false,true,"SALES_PAUSED")==TrafficMetrics.Outcome.SERVICE_PAUSED,"pause classification");
        HttpServer backend=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        backend.createContext("/",e->{try(e){byte[] b={1,2,3};e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);}});backend.start();
        try {
            var p=new Properties();p.setProperty("loadbalancer.strategy","ROUND_ROBIN");p.setProperty("adaptive.warmup.ms","0");p.setProperty("healthcheck.recovery.successes","1");
            var b=new BackendServer("127.0.0.1",backend.getAddress().getPort(),8);var config=new ProxyConfig(p,List.of(b));var dispatcher=new Dispatcher(config);
            dispatcher.recordHealth(b,dispatcher.beginProbe(b),true);
            try(var handler=new ProxyHandler(config,dispatcher)) {
                var exchange=new BrokenClient();
                try{handler.handle(exchange);throw new AssertionError("write failure missing");}catch(IOException expected){}
                var t=total(handler.trafficJson());check(exchange.status==200,"header already sent");
                check(number(t,"failed")==1&&number(t,"successful")==0&&number(t,"http4xx")==0&&number(t,"http5xx")==0,"real forwarding failure without HTTP error");
                check(number(map(t.get("outcomes")),"CLIENT_FAILURE")==1&&number(t,"responseInterrupted")==1,"downstream classified");
                check(dispatcher.snapshots().getFirst().completed()==1&&dispatcher.snapshots().getFirst().errors()==0,"healthy upstream not penalized or counted twice");
                var d=handler.diagnostics();check(d.activeHandlers()==0&&d.deadlineTasks()==0&&d.upstreamSockets()==0&&d.admissionAvailable()==128,"all request resources released");
                try{handler.handleManagement(new BrokenClient(),e->{throw new IOException("management fixture");});}catch(IOException expected){}
                check(number(total(handler.trafficJson()),"completed")==1,"management excluded from data counter");
            }
        }finally{backend.stop(0);}
        System.out.println("TrafficOutcomeTest PASS: all 12 outcomes, invariants, no-response, post-header IOException, management scope and resource release");
    }
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object o){return (Map<String,Object>)o;}
    static Map<String,Object> total(String json){return map(map(JsonCodec.parseResource(json.getBytes(java.nio.charset.StandardCharsets.UTF_8))).get("total"));}
    static long number(Map<String,Object> m,String key){return ((Number)m.get(key)).longValue();}
    static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
    private static final class BrokenClient extends HttpExchange {
        final Headers request=new Headers(),response=new Headers();int status=-1;
        BrokenClient(){request.set("Host","localhost");}
        public Headers getRequestHeaders(){return request;}public Headers getResponseHeaders(){return response;}
        public URI getRequestURI(){return URI.create("/api/store/images/fixture");}public String getRequestMethod(){return "GET";}
        public HttpContext getHttpContext(){return null;}public void close(){}
        public InputStream getRequestBody(){return InputStream.nullInputStream();}
        public OutputStream getResponseBody(){return new OutputStream(){public void write(int b)throws IOException{throw new IOException("client disconnected");}};}
        public void sendResponseHeaders(int code,long length){status=code;}public int getResponseCode(){return status;}
        public InetSocketAddress getRemoteAddress(){return new InetSocketAddress("127.0.0.1",12345);}public InetSocketAddress getLocalAddress(){return new InetSocketAddress("127.0.0.1",8080);}
        public String getProtocol(){return "HTTP/1.1";}public Object getAttribute(String n){return null;}public void setAttribute(String n,Object v){}
        public void setStreams(InputStream in,OutputStream out){}public HttpPrincipal getPrincipal(){return null;}
    }
}
