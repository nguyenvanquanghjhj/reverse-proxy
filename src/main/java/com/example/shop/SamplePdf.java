package com.example.shop;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Original text and PDF generated locally for this educational demo; no third-party assets. */
public final class SamplePdf {
    public static byte[] bytes(String title) {
        if(!title.matches("[A-Za-z0-9 .-]{1,80}")) throw new IllegalArgumentException("Safe ASCII title required");
        var out=new ByteArrayOutputStream(); List<Integer> offsets=new ArrayList<>();
        java.util.function.Consumer<String> put=s->out.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
        put.accept("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n");
        String stream="BT /F1 20 Tf 50 750 Td ("+title+") Tj 0 -35 Td /F1 12 Tf (PBL4 shared PostgreSQL and PDF demo. Original sample.) Tj ET\n";
        var objects=List.of("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>", "<< /Length "+stream.length()+" >>\nstream\n"+stream+"endstream");
        for(int i=0;i<objects.size();i++){offsets.add(out.size());put.accept((i+1)+" 0 obj\n"+objects.get(i)+"\nendobj\n");}
        int xref=out.size(); put.accept("xref\n0 6\n0000000000 65535 f \n");
        offsets.forEach(offset->put.accept(String.format(Locale.ROOT,"%010d 00000 n \n",offset)));
        put.accept("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n"+xref+"\n%%EOF\n");
        return out.toByteArray();
    }
}
