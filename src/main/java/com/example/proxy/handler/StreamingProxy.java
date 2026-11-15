package com.example.proxy.handler;

import com.example.proxy.backend.BackendServer;
import com.example.proxy.config.ProxyConfig;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;

/** Bounded-memory opt-in HTTP/1.1 downstream streaming for immutable downloads.
 * Requests are selected by the existing Dispatcher/health/circuit/QoS admission.
 * No retry after partial response; no transaction forwarding or client-controlled target.
 */
public final class StreamingProxy implements AutoCloseable {
    private static final long LIMIT=1024L*1024*1024;
    private static final Set<String> REQUEST_HEADERS=Set.of("range","if-range","if-none-match","if-match","accept","user-agent","via","x-request-id","x-forwarded-for","x-forwarded-proto");
    private static final Set<String> RESPONSE_HEADERS=Set.of("etag","accept-ranges","content-range","content-type","content-disposition","last-modified","x-file-sha256","x-service-outcome","via");
    private final LongAdder started=new LongAdder(),completed=new LongAdder(),failed=new LongAdder(),active=new LongAdder(),bytesForwarded=new LongAdder();
    private final LongAdder downstreamFailures=new LongAdder(),upstreamFailures=new LongAdder(),timeouts=new LongAdder(),bodies=new LongAdder();
    public String metricsJson(){return "{\"started\":"+started.sum()+",\"completed\":"+completed.sum()+",\"failed\":"+failed.sum()+",\"active\":"+active.sum()+",\"bytes\":"+bytesForwarded.sum()+",\"downstreamFailures\":"+downstreamFailures.sum()+",\"upstreamFailures\":"+upstreamFailures.sum()+",\"timeouts\":"+timeouts.sum()+",\"openBodies\":"+bodies.sum()+"}";}
    public String prometheus(){
        var out=new StringBuilder();
        String[] names={"active","open_bodies","started_total","completed_total","bytes_total","failures_total","downstream_failures_total","upstream_failures_total","timeouts_total"};
        long[] values={active.sum(),bodies.sum(),started.sum(),completed.sum(),bytesForwarded.sum(),failed.sum(),downstreamFailures.sum(),upstreamFailures.sum(),timeouts.sum()};
        for(int i=0;i<names.length;i++)out.append("# TYPE proxy_streaming_").append(names[i]).append(i<2?" gauge\n":" counter\n").append("proxy_streaming_").append(names[i]).append(' ').append(values[i]).append('\n');
        return out.toString();
    }
    private final HttpClient client;
    private final java.util.concurrent.ScheduledThreadPoolExecutor timers = new java.util.concurrent.ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform().daemon().name("streaming-deadlines").factory());
    private final long timeoutMs;
    private final int maxHeaderBytes,maxHeaderCount;
    public static final class DownstreamIOException extends IOException {
        public DownstreamIOException(IOException cause){super("Client stream write failed",cause);}
    }
    public static final class DeadlineIOException extends IOException {
        public DeadlineIOException(IOException cause){super("Streaming route deadline exceeded",cause);}
    }
    public StreamingProxy(ProxyConfig config){
        timeoutMs=config.getStreamingTimeoutMs();
        maxHeaderBytes=config.getMaxHeaderBytes();maxHeaderCount=config.getMaxHeaderCount();
        client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(config.getConnectTimeoutMs())).build();
        timers.setRemoveOnCancelPolicy(true);
    }
    public static boolean eligible(String method,String path){
        return (method.equals("GET")||method.equals("HEAD")) && path!=null && path.startsWith("/api/pbl4/files/");
    }
    public int transfer(HttpExchange downstream,BackendServer backend,Map<String,List<String>> requestHeaders,long deadlineNanos) throws IOException {
        started.increment();active.increment();
        try (var deadline=new Deadline(deadlineNanos)) {int status=transferInternal(downstream,backend,requestHeaders,deadlineNanos);completed.increment();return status;}
        catch(IOException e){
            failed.increment();
            if(Thread.currentThread().isInterrupted() || System.nanoTime()>=deadlineNanos || e instanceof HttpTimeoutException){
                timeouts.increment();throw new DeadlineIOException(e);
            }else if(e instanceof DownstreamIOException)downstreamFailures.increment();
            else upstreamFailures.increment();
            throw e;
        }
        finally{active.decrement();}
    }
    private int transferInternal(HttpExchange downstream,BackendServer backend,Map<String,List<String>> requestHeaders,long deadlineNanos) throws IOException {
        String method=downstream.getRequestMethod();
        if(!eligible(method,downstream.getRequestURI().getPath()))throw new IOException("Unsupported streaming route");
        String target=downstream.getRequestURI().toASCIIString();
        URI uri=URI.create(backend.getBaseUrl()+target);
        long remaining=deadlineNanos-System.nanoTime();
        if(remaining<=0)throw new InterruptedIOException("Streaming deadline exceeded");
        var builder=HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(Math.min(remaining,java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs))));
        for(var entry:requestHeaders.entrySet()){
            if(REQUEST_HEADERS.contains(entry.getKey().toLowerCase(Locale.ROOT))){
                for(String v:entry.getValue())builder.header(entry.getKey(),v);
            }
        }
        HttpRequest req=builder.method(method,HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<InputStream> response;
        try{response=client.send(req,HttpResponse.BodyHandlers.ofInputStream());}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new InterruptedIOException("Streaming request interrupted");}
        if(response.version()!=HttpClient.Version.HTTP_1_1){response.body().close();throw new IOException("Upstream HTTP/1.1 required");}
        bodies.increment();
        try(InputStream body=response.body()){
            var upstream=response.headers();
            long headerBytes=0;int headerCount=0;
            for(var entry:upstream.map().entrySet())for(String value:entry.getValue()) {
                headerBytes+=entry.getKey().length()+value.length()+4;headerCount++;
            }
            if(headerBytes>maxHeaderBytes||headerCount>maxHeaderCount)throw new IOException("Streaming upstream headers exceed limits");
            var lengths=upstream.allValues("Content-Length");
            var encodings=upstream.allValues("Transfer-Encoding");
            if(lengths.size()>1 || !lengths.isEmpty()&&!encodings.isEmpty()
                    || !encodings.isEmpty()&&(encodings.size()!=1||!encodings.getFirst().equalsIgnoreCase("chunked")))
                throw new IOException("Ambiguous upstream framing");
            long length=-1;
            if(!lengths.isEmpty()){
                if(!lengths.getFirst().matches("[0-9]{1,19}"))throw new IOException("Invalid upstream length");
                try{length=Long.parseLong(lengths.getFirst());}catch(NumberFormatException e){throw new IOException("Invalid upstream length",e);}
                if(length>LIMIT)throw new IOException("Streaming response exceeds 1GiB limit");
            }
            if(response.statusCode()==206) {
                var ranges=upstream.allValues("Content-Range");
                if(ranges.size()!=1)throw new IOException("Partial response requires one Content-Range");
                var match=java.util.regex.Pattern.compile("bytes ([0-9]{1,19})-([0-9]{1,19})/([0-9]{1,19})").matcher(ranges.getFirst());
                if(!match.matches())throw new IOException("Invalid partial Content-Range");
                try {
                    long start=Long.parseLong(match.group(1)),end=Long.parseLong(match.group(2)),total=Long.parseLong(match.group(3));
                    if(end<start||end>=total||end-start+1!=length)throw new IOException("Inconsistent partial Content-Range");
                }catch(NumberFormatException e){throw new IOException("Invalid partial Content-Range",e);}
            }
            var headers=downstream.getResponseHeaders();
            for(var entry:UpstreamTransport.endToEndHeaders(upstream.map()).entrySet())
                if(RESPONSE_HEADERS.contains(entry.getKey().toLowerCase(Locale.ROOT)))
                    headers.put(entry.getKey(),new ArrayList<>(entry.getValue()));
            headers.set("Cache-Control","no-store");
            headers.set("X-Content-Type-Options","nosniff");
            headers.add("Via","1.1 pbl4");
            int status=response.statusCode();
            boolean noBody=method.equals("HEAD") || status==204 || status==304 || status==416;
            if(noBody){
                if(length>=0 && status!=204 && status!=416)headers.set("Content-Length",Long.toString(length));
                sendHeaders(downstream,status,-1);return status;
            }
            // Immutable downloads have a known length. Fail closed before committing for
            // unknown framing: HttpServer cannot abort chunked output without a final chunk.
            if(length<0)throw new IOException("Streaming file response requires Content-Length");
            if(length==0){headers.set("Content-Length","0");sendHeaders(downstream,status,-1);return status;}
            sendHeaders(downstream,status,length);
            byte[] buffer=new byte[32768];long transferred=0;
            while(true){
                if(Thread.currentThread().isInterrupted() || System.nanoTime()>=deadlineNanos)
                    throw new InterruptedIOException("Streaming deadline exceeded");
                int n=body.read(buffer);
                if(n<0){if(transferred!=length)throw new EOFException("Truncated upstream body");break;}
                transferred+=n;
                if(transferred>length)throw new IOException("Upstream body exceeds declared length");
                try{downstream.getResponseBody().write(buffer,0,n);bytesForwarded.add(n);}
                catch(IOException clientFailure){throw new DownstreamIOException(clientFailure);}
            }
            return status;
        }finally{bodies.decrement();}
    }
    private static void sendHeaders(HttpExchange downstream,int status,long length)throws IOException {
        try{downstream.sendResponseHeaders(status,length);}
        catch(IOException e){throw new DownstreamIOException(e);}
    }
    private final class Deadline implements AutoCloseable,Runnable {
        private final Thread owner=Thread.currentThread();
        private final java.util.concurrent.ScheduledFuture<?> task;
        private boolean finished;
        Deadline(long expires){task=timers.schedule(this,Math.max(0,expires-System.nanoTime()),java.util.concurrent.TimeUnit.NANOSECONDS);}
        @Override public synchronized void run(){if(!finished)owner.interrupt();}
        @Override public synchronized void close(){finished=true;task.cancel(false);}
    }
    @Override public void close(){client.shutdownNow();timers.shutdownNow();}
}
