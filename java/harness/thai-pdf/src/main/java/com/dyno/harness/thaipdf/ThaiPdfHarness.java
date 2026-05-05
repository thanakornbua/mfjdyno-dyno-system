package com.dyno.harness.thaipdf;

import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.font.PdfFontFactory.EmbeddingStrategy;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import com.lowagie.text.Chunk;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class ThaiPdfHarness {
    private static final Path FONT_PATH = Paths.get("..", "app", "dashboard", "fonts", "Sarabun-Regular.ttf")
        .normalize();
    private static final Path OUTPUT_DIR = Paths.get("harness", "thai-pdf", "out");
    private static final List<SampleLine> SAMPLE_LINES = List.of(
        new SampleLine("Plate style", "ทะเบียน ก๊าฬสินธุ์ ๑๒๓๔"),
        new SampleLine("Customer", "ลูกค้า: ธนา บัวทองธนากาญจน์"),
        new SampleLine("Vehicle plate", "เลขทะเบียน: กท 4567"),
        new SampleLine("Notes", "หมายเหตุ: เครื่องยนต์มีเสียงหวีดเบาๆ ตอนเร่งเต็ม"),
        new SampleLine("Mixed labels", "Power / กำลังสูงสุด: 98.4 hp @ 8,750 rpm"),
        new SampleLine("Mixed fields", "AFR / อัตราส่วนผสม: 12.8 | Temp / อุณหภูมิ: 92.5 C")
    );

    private ThaiPdfHarness() {
    }

    public static void main(String[] args) throws Exception {
        Path fontPath = requireFont();
        Files.createDirectories(OUTPUT_DIR);

        List<Result> results = new ArrayList<>();
        results.add(writeOpenPdf(fontPath));
        results.add(writeIText(fontPath));
        results.add(writePdfBox(fontPath));

        for (Result result : results) {
            System.out.println(result.library() + " -> " + result.outputPath());
        }
        System.out.println("Thai PDF harness finished at " + Instant.now());
    }

    private static Path requireFont() {
        if (!Files.isRegularFile(FONT_PATH)) {
            throw new IllegalStateException("Expected Sarabun font at " + FONT_PATH
                + ". The Task 3 harness requires the repo-local font asset.");
        }
        return FONT_PATH;
    }

    private static Result writeOpenPdf(Path fontPath) throws Exception {
        Path output = OUTPUT_DIR.resolve("openpdf-thai-test.pdf");

        com.lowagie.text.Document document = new com.lowagie.text.Document(PageSize.A4, 48, 48, 48, 48);
        com.lowagie.text.pdf.PdfWriter.getInstance(document, Files.newOutputStream(output));
        document.open();

        BaseFont baseFont = BaseFont.createFont(fontPath.toString(), BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
        Font titleFont = new Font(baseFont, 16, Font.BOLD);
        Font bodyFont = new Font(baseFont, 13, Font.NORMAL);

        com.lowagie.text.Paragraph title = new com.lowagie.text.Paragraph("OpenPDF Thai shaping probe", titleFont);
        title.setAlignment(com.lowagie.text.Element.ALIGN_LEFT);
        title.setSpacingAfter(12);
        document.add(title);

        document.add(new com.lowagie.text.Paragraph(
            "Font: app/dashboard/fonts/Sarabun-Regular.ttf", bodyFont
        ));
        document.add(new com.lowagie.text.Paragraph(
            "Generated for manual visual inspection against current jsPDF output.", bodyFont
        ));
        document.add(Chunk.NEWLINE);

        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{1.3f, 3.7f});
        for (SampleLine line : SAMPLE_LINES) {
            PdfPCell label = new PdfPCell(new com.lowagie.text.Phrase(line.label(), bodyFont));
            PdfPCell value = new PdfPCell(new com.lowagie.text.Phrase(line.value(), bodyFont));
            label.setPadding(6);
            value.setPadding(6);
            table.addCell(label);
            table.addCell(value);
        }
        document.add(table);
        document.close();

        return new Result("OpenPDF", output);
    }

    private static Result writeIText(Path fontPath) throws Exception {
        Path output = OUTPUT_DIR.resolve("itext-thai-test.pdf");

        PdfWriter writer = new PdfWriter(Files.newOutputStream(output));
        PdfDocument pdf = new PdfDocument(writer);
        Document document = new Document(pdf);
        document.setMargins(48, 48, 48, 48);

        PdfFont font = PdfFontFactory.createFont(
            fontPath.toString(),
            PdfEncodings.IDENTITY_H,
            EmbeddingStrategy.PREFER_EMBEDDED
        );
        document.setFont(font);
        document.setFontSize(13);

        document.add(new Paragraph("iText 7 Community Thai shaping probe").setFont(font).setFontSize(16).setFixedLeading(20));
        document.add(new Paragraph("Font: app/dashboard/fonts/Sarabun-Regular.ttf").setFixedLeading(18));
        document.add(new Paragraph("Generated for manual visual inspection against current jsPDF output.").setFixedLeading(18));
        document.add(new Paragraph(""));

        for (SampleLine line : SAMPLE_LINES) {
            document.add(new Paragraph(line.label() + ": " + line.value()).setFixedLeading(18));
        }

        document.close();
        return new Result("iText 7 Community", output);
    }

    private static Result writePdfBox(Path fontPath) throws Exception {
        Path output = OUTPUT_DIR.resolve("pdfbox-thai-test.pdf");

        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);

            PDType0Font font = PDType0Font.load(document, fontPath.toFile());
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(font, 16);
                content.setLeading(20);
                content.newLineAtOffset(48, page.getMediaBox().getHeight() - 64);
                content.showText("PDFBox Thai shaping probe");
                content.newLine();
                content.setFont(font, 13);
                content.showText("Font: app/dashboard/fonts/Sarabun-Regular.ttf");
                content.newLine();
                content.showText("Generated for manual visual inspection against current jsPDF output.");
                content.newLine();
                content.newLine();

                for (SampleLine line : SAMPLE_LINES) {
                    content.showText(line.label() + ": " + line.value());
                    content.newLine();
                }
                content.endText();
            }
            document.save(output.toFile());
        }

        return new Result("PDFBox", output);
    }

    private static final class SampleLine {
        private final String label;
        private final String value;

        private SampleLine(String label, String value) {
            this.label = label;
            this.value = value;
        }

        private String label() {
            return label;
        }

        private String value() {
            return value;
        }
    }

    private static final class Result {
        private final String library;
        private final Path outputPath;

        private Result(String library, Path outputPath) {
            this.library = library;
            this.outputPath = outputPath;
        }

        private String library() {
            return library;
        }

        private Path outputPath() {
            return outputPath;
        }
    }
}
