package rag.core.parse;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rag.core.model.Clearance;
import rag.core.model.PageSpan;
import rag.core.model.SourceType;

class PdfParserTest {

    @TempDir Path tmp;

    @Test
    void parsesPagesWithoutHeadersAndRecordsSpans() throws Exception {
        var doc = PdfParser.parse(TestPdfs.sample(tmp), "test-ab-1", "Sample Module", "V.1");

        assertThat(doc.text()).doesNotContain(TestPdfs.HEADER_1).doesNotContain("Sample Module   V.1")
                .contains("Control text.").contains("footnote line");
        assertThat(doc.pages()).extracting(PageSpan::page).containsExactly(1, 2, 3);
        assertThat(doc.pages().getFirst().start()).isZero();
        assertThat(doc.pages().get(2).end()).isEqualTo(doc.text().length());
        assertThat(doc.text().substring(doc.pages().get(1).start(), doc.pages().get(1).end())).contains("More background.");
        assertThat(doc.sourceType()).isEqualTo(SourceType.REGULATORY);
        assertThat(doc.classification()).isEqualTo(Clearance.PUBLIC);
        assertThat(doc.docId()).isEqualTo("test-ab-1");
        assertThat(doc.docVersion()).isEqualTo("V.1");
    }

    @Test
    void findsSectionMarkersAcrossPages() throws Exception {
        var doc = PdfParser.parse(TestPdfs.sample(tmp), "test-ab-1", "Sample Module", "V.1");
        assertThat(doc.markers()).extracting(m -> m.path().leaf()).containsExactly("1", "1.1", "1.2", "2", "2.1");
    }

    @Test
    void pageNumbersAreStripped() throws Exception {
        var doc = PdfParser.parse(TestPdfs.sample(tmp), "test-ab-1", "Sample Module", "V.1");
        assertThat(doc.text().lines()).noneMatch(l -> l.strip().matches("\\d+"));
    }
}
