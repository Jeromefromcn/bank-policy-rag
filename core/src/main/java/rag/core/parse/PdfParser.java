package rag.core.parse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import rag.core.model.Clearance;
import rag.core.model.PageSpan;
import rag.core.model.ParsedDocument;
import rag.core.model.SourceType;

/** Parses an SPM module PDF page by page (spec 01 §3.1). Regulatory text is always {@code public}. */
public final class PdfParser {

    private PdfParser() {}

    public static ParsedDocument parse(Path pdf, String docId, String title, String docVersion) {
        List<List<String>> pages = new ArrayList<>();
        try (var doc = Loader.loadPDF(pdf.toFile())) {
            var stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setLineSeparator("\n");
            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                pages.add(stripper.getText(doc).lines().toList());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read PDF " + pdf.getFileName(), e);
        }

        List<List<String>> stripped = HeaderFooterStripper.strip(pages);
        StringBuilder text = new StringBuilder();
        List<PageSpan> spans = new ArrayList<>(stripped.size());
        for (int i = 0; i < stripped.size(); i++) {
            if (i > 0) {
                text.append('\n');
            }
            int start = text.length();
            text.append(String.join("\n", stripped.get(i)));
            spans.add(new PageSpan(i + 1, start, text.length()));
        }
        String body = text.toString();
        return new ParsedDocument(docId, title, docVersion, SourceType.REGULATORY, Clearance.PUBLIC, body, spans,
                SpmSectionMarkers.find(body));
    }
}
