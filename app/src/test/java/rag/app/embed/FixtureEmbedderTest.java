package rag.app.embed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rag.app.RagProperties;
import rag.app.fixture.FixtureFiles;

class FixtureEmbedderTest {

    @TempDir Path tmp;

    @Test
    void looksUpByPrefixedHashAndFailsLoudlyOnAMiss() {
        var cfg = new RagProperties.Embedding("nomic", 3, 16, tmp.toString(),
                Map.of("nomic", new RagProperties.ModelSpec("nomic-embed-text", "nomic", 2,
                        "search_query: ", "search_document: ")), tmp.toString());
        var chunk = new FixtureFiles.FixtureChunk("c", "h",
                FixtureFiles.textHash("search_document: cat"), new float[] {1, 2});
        var question = new FixtureFiles.FixtureQuestion("g",
                FixtureFiles.textHash("search_query: q"), new float[] {3, 4});
        Path dir = FixtureFiles.write(tmp, new FixtureFiles.FixtureManifest("c", "ollama/nomic-embed-text",
                "fixed", 0, 0, Map.of()), List.of(chunk), List.of(question));

        var embedder = new FixtureEmbedder(dir, cfg);
        assertThat(embedder.modelId()).isEqualTo("ollama/nomic-embed-text");
        assertThat(embedder.embedDocuments(List.of("cat")).getFirst()).containsExactly(1f, 2f);
        assertThat(embedder.embedQuery("q")).containsExactly(3f, 4f);
        assertThatThrownBy(() -> embedder.embedQuery("missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no fixture vector")
                .hasMessageContaining("ollama/nomic-embed-text");
    }
}
