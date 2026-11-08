package rag.core.corpus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rag.core.model.Clearance;
import rag.core.model.ParsedDocument;

class CorpusLoaderTest {

    @TempDir Path tmp;

    @Test
    void loadsAllDocumentsInOrder() throws Exception {
        var docs = new CorpusLoader(TestCorpus.create(tmp)).load();
        assertThat(docs).extracting(ParsedDocument::docId).containsExactly("test-ab-1", "adv-x", "eb-a");
        assertThat(docs.getFirst().docVersion()).isEqualTo("V.1");
        assertThat(docs.getFirst().title()).isEqualTo("Sample Module");
        assertThat(docs.get(1).classification()).isEqualTo(Clearance.PUBLIC);
    }

    @Test
    void manifestReadsDatesAsIsoStrings() throws Exception {
        var m = Manifest.load(TestCorpus.create(tmp).resolve("manifest.yaml"));
        assertThat(m.modules().getFirst().revisionDate()).isEqualTo("2020-01-01");
    }

    @Test
    void shaMismatchFails() throws Exception {
        Path root = TestCorpus.create(tmp);
        TestCorpus.writeManifest(root, "0".repeat(64));
        assertThatThrownBy(() -> new CorpusLoader(root).load()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AB-1.pdf").hasMessageContaining("sha256");
    }

    @Test
    void missingPdfFails() throws Exception {
        Path root = TestCorpus.create(tmp);
        Files.delete(root.resolve("raw/AB-1.pdf"));
        assertThatThrownBy(() -> new CorpusLoader(root).load()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AB-1.pdf");
    }

    @Test
    void duplicateDocIdFails() throws Exception {
        Path root = TestCorpus.create(tmp);
        Files.writeString(root.resolve("internal/eb-b.md"), TestCorpus.markdown("eb-a", "internal", "internal"));
        assertThatThrownBy(() -> new CorpusLoader(root).load()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate").hasMessageContaining("eb-a");
    }

    @Test
    void knownDocIdsReadsWithoutParsingPdfs() throws Exception {
        Path root = TestCorpus.create(tmp);
        Files.delete(root.resolve("raw/AB-1.pdf"));
        assertThat(CorpusLoader.knownDocIds(root)).containsExactlyInAnyOrder("test-ab-1", "adv-x", "eb-a");
    }
}
