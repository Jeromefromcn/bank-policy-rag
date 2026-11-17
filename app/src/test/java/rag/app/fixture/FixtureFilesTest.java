package rag.app.fixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FixtureFilesTest {

    @TempDir Path tmp;

    static FixtureFiles.FixtureManifest manifest() {
        return new FixtureFiles.FixtureManifest("corpus1", "ollama/nomic-embed-text", "fixed-512-64-cl100k", 0, 0,
                Map.of());
    }

    @Test
    void roundTripsVectorsAndWritesNoText() throws Exception {
        var chunk = new FixtureFiles.FixtureChunk("c1", "content1",
                FixtureFiles.textHash("search_document: tolerance for disruption"), new float[] {1, 2, 3});
        var question = new FixtureFiles.FixtureQuestion("g1",
                FixtureFiles.textHash("search_query: self made question"), new float[] {4, 5});
        Path dir = FixtureFiles.write(tmp, manifest(), List.of(chunk), List.of(question));

        assertThat(FixtureFiles.readVectors(dir, "chunks.jsonl")).containsKey(chunk.textHash());
        assertThat(FixtureFiles.readVectors(dir, "questions.jsonl")).containsKey(question.textHash());
        String files = Files.readString(dir.resolve("chunks.jsonl"))
                + Files.readString(dir.resolve("questions.jsonl"));
        assertThat(files).doesNotContain("tolerance for disruption", "self made question");
        assertThat(Files.readString(dir.resolve("manifest.json"))).contains("chunks", "questions", "corpus1");
    }

    @Test
    void textHashDependsOnThePrefix() {
        assertThat(FixtureFiles.textHash("search_query: q"))
                .isNotEqualTo(FixtureFiles.textHash("search_document: q"));
    }

    @Test
    void locateFailsWhenTheKeyIsMissing() {
        assertThatThrownBy(() -> FixtureFiles.locate(tmp, "missing", "model", "chunker"))
                .hasMessageContaining("missing").hasMessageContaining("model")
                .hasMessageContaining("build-fixture.sh");
    }
}
