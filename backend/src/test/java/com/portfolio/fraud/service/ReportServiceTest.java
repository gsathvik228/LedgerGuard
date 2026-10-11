package com.portfolio.fraud.service;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ReportServiceTest {
    @Test void csvNeutralizesFormulasAfterWhitespaceAndEscapesQuotes(){for(String text:List.of("=SUM(1,2)","+cmd","-cmd","@SUM(A1)","  =SUM(1,2)","\tformula","\rformula"))assertThat(ReportService.csvCell(text)).startsWith("\"'");assertThat(ReportService.csvCell("A \"quoted\", name")).isEqualTo("\"A \"\"quoted\"\", name\"");}
    @Test void pdfHasCorrectCrossReferenceOffsetsAndMultiplePages(){var lines=new ArrayList<String>();for(int n=0;n<110;n++)lines.add("Reference #"+n+" (description) \\ text");byte[] data=SimplePdf.render(lines);String pdf=new String(data,StandardCharsets.ISO_8859_1);assertThat(pdf).startsWith("%PDF-1.4").endsWith("%%EOF\n").contains("/Count 3");int xref=Integer.parseInt(pdf.substring(pdf.lastIndexOf("startxref\n")+10).split("\n")[0]);assertThat(pdf.substring(xref)).startsWith("xref\n");String[] entries=pdf.substring(xref).split("\n");int objects=Integer.parseInt(entries[1].split(" ")[1])-1;for(int n=0;n<objects;n++){int offset=Integer.parseInt(entries[n+3].substring(0,10));assertThat(pdf.substring(offset)).startsWith((n+1)+" 0 obj\n");}assertThat(pdf).contains("\\(description\\)","\\\\ text");}
}
