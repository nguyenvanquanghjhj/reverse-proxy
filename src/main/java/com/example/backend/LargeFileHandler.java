package com.example.backend;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Opt-in immutable large-file backend, separate from transactional Mộc Store.
 * Provision files offline by atomic rename; never upload/write from an HTTP request.
 * Default-disabled; no path outside PBL4_LARGE_FILES_ROOT can be served.
 */
public final class LargeFileHandler {
    private static final String PREFIX = "/api/pbl4/files/";
    private static final long LIMIT = 1024L * 1024 * 1024;
    private static final Set<String> EXT=Set.of("pdf","zip","bin");
    private LargeFileHandler() {}
    public static boolean matches(String path) { return path != null && path.startsWith(PREFIX); }
    public static void serve(HttpExchange e) throws IOException {
        String method=e.getRequestMethod();
        if(!method.equals("GET")&&!method.equals("HEAD")) {e.getResponseHeaders().set("Allow","GET, HEAD"); e.sendResponseHeaders(405,-1);return;}
        String raw=e.getRequestURI().getRawPath();
        String name=e.getRequestURI().getPath().substring(PREFIX.length());
        if(raw==null || !raw.startsWith(PREFIX) || !raw.substring(PREFIX.length()).equals(name)
                || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,100}") || name.contains("..")
                || name.lastIndexOf('.')<1
                || !EXT.contains(name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT))) {
            e.sendResponseHeaders(400,-1);return;
        }
        String configured=System.getenv("PBL4_LARGE_FILES_ROOT");
        if(configured==null || configured.isBlank()) { e.sendResponseHeaders(404,-1);return; }
        Path root=Path.of(configured).toAbsolutePath().normalize();
        for(Path p=root;p!=null;p=p.getParent()) {
            if(Files.exists(p,NOFOLLOW_LINKS)) {
                var a=Files.readAttributes(p,BasicFileAttributes.class,NOFOLLOW_LINKS);
                if(a.isSymbolicLink()||a.isOther()){e.sendResponseHeaders(503,-1);return;}
            }
        }
        if(!Files.isDirectory(root,NOFOLLOW_LINKS)) {e.sendResponseHeaders(503,-1);return;}
        if(!root.toRealPath().equals(root)){e.sendResponseHeaders(503,-1);return;}
        Path file=root.resolve(name);
        if(!Files.isRegularFile(file,NOFOLLOW_LINKS)) {e.sendResponseHeaders(404,-1);return;}
        BasicFileAttributes attrs=Files.readAttributes(file,BasicFileAttributes.class,NOFOLLOW_LINKS);
        long size=attrs.size();
        if(size<=0||size>LIMIT){e.sendResponseHeaders(413,-1);return;}
        // Strong ETag is computed without buffering file in heap. Immutable publication
        // is an operator contract; concurrent writes are rejected by the post-hash stat.
        // Hold the same no-follow descriptor for digest AND transfer. Atomic publication
        // cannot switch the bytes underneath the ETag between two path-based opens.
        try(FileChannel channel=FileChannel.open(file,StandardOpenOption.READ,NOFOLLOW_LINKS)) {
        String etag=hash(channel);
        BasicFileAttributes after=Files.readAttributes(file,BasicFileAttributes.class,NOFOLLOW_LINKS);
        if(!java.util.Objects.equals(after.fileKey(),attrs.fileKey())||after.size()!=size||!after.lastModifiedTime().equals(attrs.lastModifiedTime())) {
            e.sendResponseHeaders(503,-1);return;
        }
        var h=e.getResponseHeaders();
        h.set("ETag","\""+etag+"\"");
        h.set("Accept-Ranges","bytes");
        h.set("Cache-Control","private, no-store");
        h.set("X-Content-Type-Options","nosniff");
        h.set("Content-Type",name.toLowerCase(Locale.ROOT).endsWith(".pdf")?"application/pdf":name.toLowerCase(Locale.ROOT).endsWith(".zip")?"application/zip":"application/octet-stream");
        h.set("Content-Disposition","attachment; filename=\""+name+"\"");
        String quoted="\""+etag+"\"";
        String ifMatch=String.join(",",e.getRequestHeaders().getOrDefault("If-Match",java.util.List.of()));
        if(!ifMatch.isEmpty() && !tagMatches(ifMatch,quoted,false)){e.sendResponseHeaders(412,-1);return;}
        String ifNone=String.join(",",e.getRequestHeaders().getOrDefault("If-None-Match",java.util.List.of()));
        if(!ifNone.isEmpty() && tagMatches(ifNone,quoted,true)){e.sendResponseHeaders(304,-1);return;}
        String range=e.getRequestHeaders().getFirst("Range");
        if(e.getRequestHeaders().getOrDefault("Range",java.util.List.of()).size()>1)range="invalid";
        String ifRange=e.getRequestHeaders().getFirst("If-Range");
        if(e.getRequestHeaders().getOrDefault("If-Range",java.util.List.of()).size()>1)range=null;
        if(range!=null && ifRange!=null && !ifRange.equals("\""+etag+"\"")) range=null;
        // Range applies to GET only (RFC 9110 section 14.2).
        RangeRequest.Selection part=RangeRequest.select(method.equals("HEAD")?null:range,size);
        if(part.contentRange()!=null) h.set("Content-Range",part.contentRange());
        if(part.status()==416){e.sendResponseHeaders(416,-1);return;}
        h.set("Content-Length",Long.toString(part.length()));
        if(method.equals("HEAD")){e.sendResponseHeaders(part.status(),-1);return;}
        channel.position(part.start());
        e.sendResponseHeaders(part.status(),part.length());
            byte[] buffer=new byte[32768];long left=part.length();
            ByteBuffer block=ByteBuffer.wrap(buffer);
            var out=e.getResponseBody();
            while(left>0){
                if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("File transfer cancelled");
                block.clear().limit((int)Math.min(buffer.length,left));
                int count=channel.read(block);
                if(count<0)throw new IOException("File truncated during streaming");
                out.write(buffer,0,count);left-=count;}
        }
    }
    private static boolean tagMatches(String header,String etag,boolean weak) {
        for(String token:header.split(",")) {
            token=token.trim();if(token.equals("*"))return true;
            if(weak&&token.startsWith("W/"))token=token.substring(2);
            if(token.equals(etag))return true;
        }
        return false;
    }
    private static String hash(FileChannel channel)throws IOException {
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
                byte[] buffer=new byte[32768];int n;
                ByteBuffer block=ByteBuffer.wrap(buffer);
                while((n=channel.read(block))!=-1){
                    if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("Digest cancelled");
                    digest.update(buffer,0,n);block.clear();
                }
            return HexFormat.of().formatHex(digest.digest());
        }catch(NoSuchAlgorithmException ex){throw new IOException("SHA-256 unavailable",ex);}
    }
}
