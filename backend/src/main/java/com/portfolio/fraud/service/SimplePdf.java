package com.portfolio.fraud.service;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Small text-only, paginated PDF writer. Standard Helvetica; unsupported glyphs become ?. */
public final class SimplePdf {
    private SimplePdf(){}
    private static byte[] bytes(String s){return s.getBytes(StandardCharsets.ISO_8859_1);}
    public static byte[] render(List<String> lines) {
        int pages=Math.max(1,(lines.size()+47)/48);List<String> objects=new ArrayList<>();
        objects.add("<< /Type /Catalog /Pages 2 0 R >>");
        StringBuilder kids=new StringBuilder();for(int i=0;i<pages;i++)kids.append(4+2*i).append(" 0 R ");
        objects.add("<< /Type /Pages /Kids ["+kids+"] /Count "+pages+" >>");objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>");
        for(int page=0;page<pages;page++){
            objects.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> /Contents "+(5+2*page)+" 0 R >>");
            StringBuilder content=new StringBuilder("BT /F1 9 Tf 40 750 Td 14 TL\n");
            for(int n=page*48;n<Math.min((page+1)*48,lines.size());n++){String line=lines.get(n).replaceAll("[^\\x20-\\x7E]","?").replace("\\","\\\\").replace("(","\\(").replace(")","\\)");content.append('(').append(line).append(") Tj T*\n");}
            content.append("(Page ").append(page+1).append(" of ").append(pages).append(") Tj\nET\n");
            objects.add("<< /Length "+bytes(content.toString()).length+" >>\nstream\n"+content+"endstream");
        }
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.writeBytes(bytes("%PDF-1.4\n"));List<Integer> offsets=new ArrayList<>();
        for(int i=0;i<objects.size();i++){offsets.add(out.size());out.writeBytes(bytes((i+1)+" 0 obj\n"+objects.get(i)+"\nendobj\n"));}
        int xref=out.size();out.writeBytes(bytes("xref\n0 "+(objects.size()+1)+"\n0000000000 65535 f \n"));for(int offset:offsets)out.writeBytes(bytes(String.format(Locale.ROOT,"%010d 00000 n \n",offset)));
        out.writeBytes(bytes("trailer\n<< /Size "+(objects.size()+1)+" /Root 1 0 R >>\nstartxref\n"+xref+"\n%%EOF\n"));return out.toByteArray();
    }
}
