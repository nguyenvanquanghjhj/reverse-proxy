package com.example.store.storage;

import com.example.shop.*;
import com.example.store.validation.Input;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.util.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Server-generated opaque keys; normalized images and bounded verified reads. */
public final class FileStorage {
    public static final int LIMIT=524288;
    public record Content(byte[] bytes,String mime) {}
    private final Path root;private final int pdfLimit;
    public FileStorage(ShopConfig config)throws IOException {
        root=config.filesDir().resolve("store").normalize();pdfLimit=Math.min(LIMIT,config.maxPdfBytes());
        noLinks(root);Files.createDirectories(root);verifyRoot();
    }
    private static void noLinks(Path p)throws IOException{for(;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("Linked storage");}
    private void verifyRoot()throws IOException {noLinks(root);if(!Files.isDirectory(root,NOFOLLOW_LINKS)||!root.toRealPath().equals(root)||!Files.isReadable(root))throw new IOException("Storage unavailable");}
    private Path path(String key)throws IOException {verifyRoot();if(key==null||!key.matches("[0-9a-f-]{36}\\.bin"))throw new IOException("Invalid key");Path p=root.resolve(key);if(Files.isSymbolicLink(p))throw new IOException("Linked file");return p;}
    public Content validate(byte[] bytes,String mime,String kind)throws IOException {
        if(bytes.length>LIMIT)throw new ShopException(413,"FILE_LIMIT","Tệp tối đa 512 KiB; PDF theo giới hạn cấu hình.");
        if(kind.equals("PDF")){if(!"application/pdf".equals(mime))throw Input.bad("Vui lòng chọn tệp PDF.");SharedStorage.validatePdf(bytes,pdfLimit);return new Content(bytes,"application/pdf");}
        if(!Set.of("image/png","image/jpeg").contains(mime))throw Input.bad("Ảnh cần ở định dạng PNG hoặc JPEG.");
        boolean png=bytes.length>8&&bytes[0]==(byte)137&&bytes[1]==80&&bytes[2]==78&&bytes[3]==71;
        boolean jpg=bytes.length>3&&bytes[0]==(byte)255&&bytes[1]==(byte)216&&bytes[2]==(byte)255;
        if(mime.equals("image/png")?!png:!jpg)throw Input.bad("Nội dung ảnh không đúng định dạng.");
        try(var in=new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))){var readers=ImageIO.getImageReaders(in);if(!readers.hasNext())throw Input.bad("Không đọc được ảnh.");var reader=readers.next();
            try{reader.setInput(in);int w=reader.getWidth(0),h=reader.getHeight(0);if(w<32||h<32||w>2400||h>2400||(long)w*h>4000000)throw Input.bad("Ảnh cần từ 32 px, tối đa 2400 px mỗi chiều và 4 triệu điểm ảnh.");
                BufferedImage image=reader.read(0);var out=new ByteArrayOutputStream();if(!ImageIO.write(image,"png",out))throw new IOException("Image encoder unavailable");
                if(out.size()>LIMIT)throw new ShopException(413,"FILE_LIMIT","Ảnh sau xử lý vượt 512 KiB. Vui lòng giảm kích thước.");return new Content(out.toByteArray(),"image/png");
            }finally{reader.dispose();}}
        catch(javax.imageio.IIOException e){throw Input.bad("Ảnh bị hỏng hoặc không thể đọc.");}
    }
    public String save(byte[] bytes)throws IOException {
        if(bytes.length<1||bytes.length>LIMIT)throw Input.bad("Dung lượng tệp không hợp lệ.");String key=UUID.randomUUID()+".bin";Path destination=path(key),temp=Files.createTempFile(root,".upload-",".tmp");
        try{try(var channel=FileChannel.open(temp,StandardOpenOption.WRITE,NOFOLLOW_LINKS)){var b=java.nio.ByteBuffer.wrap(bytes);while(b.hasRemaining())channel.write(b);channel.force(true);}Files.move(temp,destination,StandardCopyOption.ATOMIC_MOVE);return key;}
        finally{Files.deleteIfExists(temp);}
    }
    public byte[] read(String key,int size,String hash)throws IOException {
        if(size<1||size>LIMIT)throw new IOException("Invalid size");Path file=path(key);if(!Files.isRegularFile(file,NOFOLLOW_LINKS))throw new NoSuchFileException("File missing");
        try(var channel=FileChannel.open(file,StandardOpenOption.READ,NOFOLLOW_LINKS);var in=Channels.newInputStream(channel)){
            if(channel.size()!=size)throw new IOException("Size mismatch");byte[] bytes=in.readNBytes(LIMIT+1);if(bytes.length!=size||!OrderRequest.sha256(bytes).equals(hash))throw new IOException("Checksum mismatch");return bytes;}
    }
    public void probe()throws IOException {
        verifyRoot();Path temp=Files.createTempFile(root,".probe-",".tmp");
        try {byte[] bytes={80,66,76,52};Files.write(temp,bytes,StandardOpenOption.WRITE,NOFOLLOW_LINKS);
            if(!Arrays.equals(bytes,Files.readAllBytes(temp)))throw new IOException("Storage probe mismatch");
        } finally {Files.deleteIfExists(temp);}
    }
    public void discard(String key)throws IOException {Files.deleteIfExists(path(key));}
    public List<String> keys()throws IOException {verifyRoot();try(var paths=Files.list(root)){return paths.map(p->p.getFileName().toString()).toList();}}
}
