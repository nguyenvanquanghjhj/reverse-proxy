package com.example.shop;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.*;

/** Operator-owned local shared directory. Never derive a physical path from a client filename. */
public final class SharedStorage {
    private final Path root;
    private final int limit;
    public SharedStorage(Path directory,int limit) throws IOException {
        root=directory.toAbsolutePath().normalize(); this.limit=limit;
        noLinks(root); Files.createDirectories(root); verifyRoot();
        if(!Files.isDirectory(root,NOFOLLOW_LINKS) || !Files.isReadable(root) || !Files.isWritable(root)) throw new IOException("Shared storage must be readable and writable");
        Path probe=Files.createTempFile(root,".probe-",".tmp"); Files.delete(probe);
    }
    private static void noLinks(Path path) throws IOException {
        for(Path p=path;p!=null;p=p.getParent()) if(Files.isSymbolicLink(p)) throw new IOException("Linked storage is not allowed");
    }
    private void verifyRoot() throws IOException {
        noLinks(root);
        if(!root.toRealPath().equals(root)) throw new IOException("Storage path must not contain links");
        if(!Files.isDirectory(root,NOFOLLOW_LINKS) || !Files.isReadable(root))
            throw new IOException("Shared storage directory unavailable");
    }
    public static String filename(String name) {
        if(name==null || name.length()>100 || !name.matches("[A-Za-z0-9][A-Za-z0-9 _.-]*\\.pdf") || name.contains(".."))
            throw ShopException.invalid("X-Document-Name must be a safe ASCII .pdf filename, at most 100 characters");
        return name;
    }
    public static void validatePdf(byte[] bytes,int max) {
        if(bytes.length>max) throw new ShopException(413,"PDF_LIMIT","PDF exceeds configured limit");
        if(bytes.length<10 || !new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-")
                || !new String(bytes,Math.max(0,bytes.length-1024),Math.min(1024,bytes.length),StandardCharsets.ISO_8859_1).stripTrailing().endsWith("%%EOF"))
            throw ShopException.invalid("Expected a PDF signature and EOF marker");
    }
    public Path path(String key) throws IOException {
        verifyRoot();
        if(key==null || !key.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.pdf")) throw new IOException("Invalid storage key");
        Path file=root.resolve(key).normalize();
        if(!file.getParent().equals(root) || Files.isSymbolicLink(file)) throw new IOException("Unsafe document path");
        return file;
    }
    public String save(byte[] bytes) throws IOException {
        validatePdf(bytes,limit); verifyRoot();
        String key=UUID.randomUUID()+".pdf"; Path dest=path(key);
        Path temporary=Files.createTempFile(root,".upload-",".tmp");
        try {
            try(var channel=FileChannel.open(temporary,WRITE,NOFOLLOW_LINKS)) {
                var buffer=java.nio.ByteBuffer.wrap(bytes); while(buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            // Fail closed on filesystems without atomic rename; supported local NTFS/ext4 paths work.
            Files.move(temporary,dest,StandardCopyOption.ATOMIC_MOVE);
            return key;
        } finally { Files.deleteIfExists(temporary); }
    }
    public byte[] read(String key,long size,String hash) throws IOException {
        if(size<1 || size>limit) throw new IOException("Stored PDF exceeds configured budget");
        Path file=path(key);
        if(!Files.isRegularFile(file,NOFOLLOW_LINKS)) throw new NoSuchFileException("Document missing");
        try(var channel=FileChannel.open(file,READ,NOFOLLOW_LINKS);var in=Channels.newInputStream(channel)) {
            if(channel.size()!=size) throw new IOException("Document size mismatch");
            byte[] bytes=in.readNBytes(limit+1);
            if(bytes.length!=size || !OrderRequest.sha256(bytes).equals(hash)) throw new IOException("Document checksum mismatch");
            return bytes;
        }
    }
    public void delete(String key) throws IOException { Files.deleteIfExists(path(key)); }
    public Path root() { return root; }
}
