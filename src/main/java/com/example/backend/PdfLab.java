package com.example.backend;

import com.example.shop.JsonCodec;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** Explicit, local-operator PDF import. Fixed HTTPS source; immutable QA files only. */
public final class PdfLab implements AutoCloseable {
    public static final String SOURCE = "https://www.w3.org/WAI/ER/tests/xhtml/testfiles/resources/pdf/dummy.pdf";
    public static final String LICENSE = "https://www.w3.org/copyright/document-license-2023/";
    public static final long LIMIT = 16L * 1024 * 1024;
    private final boolean enabled;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("pdf-import").factory());
    private volatile Map<String,Object> receipt = Map.of("state","EMPTY");
    private volatile HttpsURLConnection connection;
    private boolean busy, closed;
    public PdfLab(boolean streaming) { enabled = streaming && "true".equals(System.getenv("PBL4_PDF_LAB_ENABLED")); }
    public synchronized String status() {
        if(enabled&&!busy&&"EMPTY".equals(receipt.get("state")))try {
            var cached=readCache(root());if(cached!=null)receipt=cached;
        }catch(Exception e){receipt=Map.of("state","FAILED","error","Cache PDF không hợp lệ hoặc kho QA chưa sẵn sàng.");}
        return JsonCodec.write(Map.of("enabled",enabled,"busy",busy,"source_id","w3c-dummy","name","W3C — PDF kiểm thử",
                "source",SOURCE,"license",LICENSE,"max_bytes",LIMIT,"receipt",receipt));
    }
    public synchronized boolean begin(String source) {
        if(!enabled || closed || busy || !"w3c-dummy".equals(source))return false;
        busy=true;receipt=Map.of("state","IMPORTING");
        worker.execute(()->{
            try { receipt=importPdf(); }
            catch(Exception e) { receipt=Map.of("state","FAILED","error","Không nhập được PDF: "+e.getClass().getSimpleName()+". Kiểm tra mạng, chứng chỉ và kho thử nghiệm."); }
            finally { synchronized(this){busy=false;} }
        });return true;
    }
    public static boolean publicAddress(InetAddress address) {
        if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress())return false;
        byte[] b=address.getAddress();
        if(b.length==4){int a=b[0]&255,c=b[1]&255;
            return a!=0&&a!=10&&a!=127&&a<224&&!(a==100&&c>=64&&c<=127)&&!(a==169&&c==254)
                    &&!(a==172&&c>=16&&c<=31)&&!(a==192&&(c==0||c==168))&&!(a==198&&(c==18||c==19||c==51))&&!(a==203&&c==0);
        }
        // Only global-unicast IPv6; reject ULA, mapped/private, transition and documentation ranges.
        return (b[0]&255)>=0x20&&(b[0]&255)<=0x3f && !address.getHostAddress().startsWith("2001:db8:")
                && !address.getHostAddress().startsWith("2001:0:") && !address.getHostAddress().startsWith("2002:");
    }
    public static InetSocketAddress pinnedDestination(InetAddress verified,SocketAddress requested)throws IOException {
        if(!publicAddress(verified)||!(requested instanceof InetSocketAddress target)||target.getPort()!=443)
            throw new IOException("TLS destination refused");
        return new InetSocketAddress(verified,443);
    }
    private static Path root()throws IOException {
        String configured=System.getenv("PBL4_LARGE_FILES_ROOT");if(configured==null)throw new IOException("QA root required");
        Path root=Path.of(configured).toAbsolutePath().normalize();
        String store=System.getenv("SHOP_FILES_DIR");
        if(store!=null){Path business=Path.of(store).toAbsolutePath().normalize();if(root.startsWith(business)||business.startsWith(root))throw new IOException("Separate QA root required");}
        for(Path p=root;p!=null;p=p.getParent())if(Files.exists(p,LinkOption.NOFOLLOW_LINKS)){
            var attrs=Files.readAttributes(p,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(attrs.isSymbolicLink()||attrs.isOther())throw new IOException("Linked QA root");
        }
        if(!Files.isDirectory(root)||!root.toRealPath().equals(root))throw new IOException("QA root unavailable");
        return root;
    }
    private Map<String,Object> readCache(Path root)throws Exception {
        Path file=root.resolve("w3c-dummy.pdf"),metadata=root.resolve("w3c-dummy.receipt.json");
        if(Files.exists(file)||Files.exists(metadata)){
            if(Files.isSymbolicLink(file)||Files.isSymbolicLink(metadata)||Files.size(metadata)>8192)throw new IOException("Invalid cached receipt");
            @SuppressWarnings("unchecked") var saved=(Map<String,Object>)JsonCodec.parseResource(Files.readAllBytes(metadata));
            if(!"READY".equals(saved.get("state"))||!SOURCE.equals(saved.get("source"))
                    ||!"/api/pbl4/files/w3c-dummy.pdf".equals(saved.get("path"))||!LICENSE.equals(saved.get("license")))throw new IOException("Cache source policy");
            try(var in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){
                var digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[32768];long count=0;int n;
                while((n=in.read(buffer))!=-1){count+=n;if(count>LIMIT)throw new IOException("Cached PDF exceeds limit");digest.update(buffer,0,n);}
                if(!HexFormat.of().formatHex(digest.digest()).equals(saved.get("sha256"))||count!=((Number)saved.get("size_bytes")).longValue())throw new IOException("Cache integrity mismatch");
            }return saved;
        }
        return null;
    }
    private Map<String,Object> importPdf()throws Exception {
        Path root=root(),file=root.resolve("w3c-dummy.pdf"),metadata=root.resolve("w3c-dummy.receipt.json");
        var cached=readCache(root);if(cached!=null)return cached;
        long started=System.nanoTime(),deadline=started+TimeUnit.SECONDS.toNanos(25);
        URI uri=URI.create(SOURCE);InetAddress[] addresses=InetAddress.getAllByName(uri.getHost());
        if(addresses.length==0||Arrays.stream(addresses).anyMatch(a->!publicAddress(a)))throw new IOException("Non-public source DNS");
        var target=addresses[0];
        connection=(HttpsURLConnection)uri.toURL().openConnection(Proxy.NO_PROXY);
        connection.setSSLSocketFactory(new PinnedTls(uri.getHost(),target));
        connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(3000);connection.setReadTimeout(3000);
        connection.setRequestProperty("Accept","application/pdf");connection.setRequestProperty("Accept-Encoding","identity");
        connection.setRequestProperty("User-Agent","PBL4-PDF-Lab/1");
        Path temp=Files.createTempFile(root,".pdf-import-",".tmp");
        try {
            int status=connection.getResponseCode();
            if(status!=200||!"application/pdf".equalsIgnoreCase(connection.getContentType().split(";",2)[0].strip())||connection.getContentLengthLong()>LIMIT||connection.getHeaderField("Content-Encoding")!=null)throw new IOException("Source HTTP policy");
            var digest=MessageDigest.getInstance("SHA-256");long count=0;byte[] prefix=new byte[5],tail=new byte[1024];int tailSize=0;
            try(var in=connection.getInputStream();var out=Files.newOutputStream(temp)){
                byte[] buffer=new byte[32768];int n;
                while((n=in.read(buffer))!=-1){
                    if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)throw new InterruptedIOException("Import deadline");
                    if(count+n>LIMIT)throw new IOException("PDF exceeds limit");
                    for(int i=0;i<n&&count+i<5;i++)prefix[(int)count+i]=buffer[i];
                    if(n>=tail.length){System.arraycopy(buffer,n-tail.length,tail,0,tail.length);tailSize=tail.length;}
                    else{int keep=Math.min(tailSize,tail.length-n);System.arraycopy(tail,tailSize-keep,tail,0,keep);System.arraycopy(buffer,0,tail,keep,n);tailSize=keep+n;}
                    digest.update(buffer,0,n);out.write(buffer,0,n);count+=n;
                }
            }
            if(!Arrays.equals(prefix,"%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII))||!new String(tail,0,tailSize,java.nio.charset.StandardCharsets.ISO_8859_1).contains("%%EOF")||count<10||connection.getContentLengthLong()>=0&&count!=connection.getContentLengthLong())throw new IOException("Invalid or incomplete PDF");
            var result=Map.<String,Object>of("state","READY","path","/api/pbl4/files/w3c-dummy.pdf","source",SOURCE,"license",LICENSE,
                    "notice","Copyright © W3C. Original document and license linked above.","size_bytes",count,"sha256",HexFormat.of().formatHex(digest.digest()),"import_ms",(System.nanoTime()-started)/1e6,"imported_utc",java.time.Instant.now().toString());
            Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE);
            Files.writeString(metadata,JsonCodec.write(result),StandardOpenOption.CREATE_NEW);
            return result;
        }finally{connection.disconnect();connection=null;Files.deleteIfExists(temp);}
    }
    /** Pin the validated destination at connect time, retaining SNI and HTTPS hostname verification. */
    private static final class PinnedTls extends SSLSocketFactory {
        private final String host;private final InetAddress address;
        private final SSLSocketFactory delegate=(SSLSocketFactory)SSLSocketFactory.getDefault();
        PinnedTls(String host,InetAddress address){this.host=host;this.address=address;}
        public String[] getDefaultCipherSuites(){return delegate.getDefaultCipherSuites();}
        public String[] getSupportedCipherSuites(){return delegate.getSupportedCipherSuites();}
        // HttpsURLConnection opens its TCP socket BEFORE createSocket(socket,host,...).
        // Pin that initial connect too; otherwise DNS rebinding can cause an unwanted SYN.
        public Socket createSocket(){return new Socket(){
            @Override public void connect(SocketAddress endpoint,int timeout)throws IOException {
                super.connect(pinnedDestination(address,endpoint),Math.min(timeout>0?timeout:3000,3000));
            }
            @Override public void connect(SocketAddress endpoint)throws IOException {connect(endpoint,3000);}
        };}
        private Socket connect(String name,int port)throws IOException {
            if(!host.equals(name)||port!=443||!publicAddress(address))throw new IOException("TLS destination refused");
            Socket tcp=new Socket();try{tcp.connect(new InetSocketAddress(address,443),3000);tcp.setSoTimeout(3000);
                return wrap(tcp,host,443,true);
            }catch(IOException|RuntimeException e){tcp.close();throw e;}
        }
        private Socket wrap(Socket socket,String name,int port,boolean close)throws IOException {
            if(!host.equals(name)||port!=443||!publicAddress(address)||!address.equals(socket.getInetAddress())||socket.getPort()!=443)
                throw new IOException("Unexpected TLS peer");
            SSLSocket tls=(SSLSocket)delegate.createSocket(socket,host,443,close);var params=tls.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");params.setServerNames(List.of(new SNIHostName(host)));tls.setSSLParameters(params);tls.startHandshake();return tls;
        }
        public Socket createSocket(Socket s,String h,int p,boolean close)throws IOException {return wrap(s,h,p,close);}
        public Socket createSocket(String h,int p)throws IOException{return connect(h,p);}
        public Socket createSocket(String h,int p,InetAddress l,int lp)throws IOException{return connect(h,p);}
        public Socket createSocket(InetAddress h,int p)throws IOException{throw new IOException("Host name required");}
        public Socket createSocket(InetAddress h,int p,InetAddress l,int lp)throws IOException{throw new IOException("Host name required");}
    }
    public synchronized void close(){closed=true;worker.shutdownNow();var c=connection;if(c!=null)c.disconnect();}
    public static void main(String[] args)throws Exception {
        if(args.length!=1||!args[0].equals("import"))throw new IllegalArgumentException("PdfLab import; set opt-in and QA root explicitly");
        try(var lab=new PdfLab(true)){
            if(!lab.begin("w3c-dummy"))throw new IllegalStateException("PDF lab disabled");
            for(int i=0;i<300&&lab.busy;i++)Thread.sleep(100);
            System.out.write((lab.status()+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));if(!"READY".equals(lab.receipt.get("state")))throw new IOException("PDF import did not complete");
        }
    }
}
