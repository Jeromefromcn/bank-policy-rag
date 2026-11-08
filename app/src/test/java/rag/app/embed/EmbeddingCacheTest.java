package rag.app.embed;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EmbeddingCacheTest {

    @TempDir Path dir;

    @Test
    void roundTrip() {
        var cache = new EmbeddingCache(dir);
        cache.put("ollama/nomic-embed-text", "ab12", new float[] {1f, -2.5f});
        assertThat(cache.get("ollama/nomic-embed-text", "ab12")).hasValueSatisfying(v -> assertThat(v).containsExactly(1f, -2.5f));
    }

    @Test
    void missIsEmpty() {
        assertThat(new EmbeddingCache(dir).get("ollama/nomic-embed-text", "nope")).isEmpty();
    }

    @Test
    void fileLayout() {
        new EmbeddingCache(dir).put("ollama/nomic-embed-text", "ab12", new float[] {1f});
        Path f = dir.resolve("ollama_nomic-embed-text/ab12.f32");
        assertThat(f).exists();
        assertThat(f.toFile().length()).isEqualTo(4);
    }

    @Test
    void modelsDoNotShareEntries() {
        var cache = new EmbeddingCache(dir);
        cache.put("ollama/a", "h", new float[] {1f});
        assertThat(cache.get("ollama/b", "h")).isEmpty();
        assertThat(Files.exists(dir.resolve("ollama_a/h.f32"))).isTrue();
    }
}
