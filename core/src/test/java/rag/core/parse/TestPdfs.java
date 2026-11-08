package rag.core.parse;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** Builds small PDFs from self-made text, shaped like a manual module: header, footer, Structure list. */
public final class TestPdfs {

    public static final String HEADER_1 = "Policy Manual";
    public static final String HEADER_2 = "AB-1   Sample Module   V.1 - 01.01.2020";

    private TestPdfs() {}

    public static Path sample(Path dir) throws IOException {
        return write(dir.resolve("AB-1.pdf"), List.of(
                List.of("Purpose", "Self-made purpose text.", "Structure", "1.  Introduction", "2.  Controls",
                        "1.  Introduction", "1.1  Background text here."),
                List.of("1.2  More background.", "2.  Controls"),
                List.of("2.1  Control text.", "1 This is a footnote line.")));
    }

    /** Writes one page per entry; every page gets the two header lines and a page-number footer. */
    public static Path write(Path file, List<List<String>> bodies) throws IOException {
        var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        try (var doc = new PDDocument()) {
            for (int p = 0; p < bodies.size(); p++) {
                var page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (var cs = new PDPageContentStream(doc, page)) {
                    float y = 800;
                    line(cs, font, 60, y, HEADER_1);
                    line(cs, font, 60, y -= 14, HEADER_2);
                    y -= 30;
                    for (String text : bodies.get(p)) {
                        line(cs, font, text.matches("^\\d+\\.\\d.*") ? 90 : 70, y, text);
                        y -= 16;
                    }
                    line(cs, font, 290, 40, Integer.toString(p + 1));
                }
            }
            doc.save(file.toFile());
        }
        return file;
    }

    private static void line(PDPageContentStream cs, PDType1Font font, float x, float y, String text) throws IOException {
        cs.beginText();
        cs.setFont(font, 11);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }
}
