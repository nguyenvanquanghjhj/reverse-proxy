package com.example.store.storage;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reference specification for a real model, generated offline with a standard PDF font. */
public final class ProductPdf {
    private ProductPdf() {}
    public static byte[] bytes(String name,String screen,String chipset,int ramGb,int batteryMah) {
        var lines=List.of("MOC STORE / MODEL REFERENCE",name,
            "Reference facts for a manufactured phone model.",
            "See the product page for dated manufacturer sources and regional notes.",
            "Screen: "+screen,"Chipset: "+chipset,"RAM: "+ramGb+" GB","Battery: "+batteryMah+" mAh",
            "Storage choices and colours vary by model and region.",
            "Choose an available colour and storage configuration on the product page.",
            "Store prices and inventory are illustrative demo data.",
            "Compiled by Moc Store; not a manufacturer-issued document or certification.");
        var stream=new StringBuilder("BT /F1 11 Tf 48 780 Td ");
        for(int i=0;i<lines.size();i++){
            String text=lines.get(i);if(text.length()>100||text.codePoints().anyMatch(c->c<32||c>126))throw new IllegalArgumentException("PDF uses bounded ASCII reference notes");
            if(i>0)stream.append("0 -").append(i==2?40:27).append(" Td ");
            stream.append("/F1 ").append(i==1?22:11).append(" Tf (").append(text.replace("\\","\\\\").replace("(","\\(").replace(")","\\)")).append(") Tj ");
        }
        stream.append("ET\n");byte[] content=stream.toString().getBytes(StandardCharsets.ISO_8859_1);
        var objects=List.of("<< /Type /Catalog /Pages 2 0 R >>","<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>","<< /Length "+content.length+" >>\nstream\n"+stream+"endstream");
        var out=new ByteArrayOutputStream();var offsets=new ArrayList<Integer>();
        java.util.function.Consumer<String> put=s->out.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));put.accept("%PDF-1.4\n%âãÏÓ\n");
        for(int i=0;i<objects.size();i++){offsets.add(out.size());put.accept((i+1)+" 0 obj\n"+objects.get(i)+"\nendobj\n");}
        int xref=out.size();put.accept("xref\n0 6\n0000000000 65535 f \n");for(int offset:offsets)put.accept(String.format(Locale.ROOT,"%010d 00000 n \n",offset));
        put.accept("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n"+xref+"\n%%EOF\n");return out.toByteArray();
    }
}
