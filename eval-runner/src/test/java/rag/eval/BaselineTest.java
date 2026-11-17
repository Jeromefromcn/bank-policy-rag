package rag.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BaselineTest {

    @TempDir Path tmp;

    static Baseline sample() {
        return new Baseline("example-bank", "corpus1", "structural-v1-400-cl100k", "ollama/nomic-embed-text", "HYBRID",
                10, false, 30, 24, 6, 12, new Baseline.Overall(0.821, 0.717, 0.750, 24),
                Map.of("factual", new Baseline.Overall(0.9, 0.8, 0.85, 10)), "2026-11-17T00:00:00Z");
    }

    @Test
    void roundTripsTheSpecShape() {
        Path file = tmp.resolve("baseline.json");
        Baseline.write(file, sample());
        assertThat(Baseline.read(file)).isEqualTo(sample());
    }

    @Test
    void rejectsUnknownFields() throws Exception {
        Path file = tmp.resolve("baseline.json");
        Baseline.write(file, sample());
        Files.writeString(file, Files.readString(file).replaceFirst("\\{", "{\"unknown\":1,"));
        assertThatThrownBy(() -> Baseline.read(file)).isInstanceOf(RuntimeException.class);
    }
}
