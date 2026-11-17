package rag.app.ci;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import rag.app.fixture.FixtureFiles;
import rag.app.ingest.IngestCommand;
import rag.app.support.IntegrationTest;
import rag.core.corpus.CorpusVersion;
import rag.core.model.Clearance;
import rag.eval.CiGate;

/**
 * The PR gate (spec 05 §7.2): Example Bank corpus, fixture embeddings, Testcontainers ES. Excluded from
 * {@code :app:test}; run by {@code :app:ciGate}.
 */
@Tag("ci")
class CiEvalGateIT extends IntegrationTest {

    static final Path FIXTURES = Path.of("eval/fixtures");
    static final String CHUNKER = "structural-v1-400-cl100k";

    @DynamicPropertySource
    static void fixture(DynamicPropertyRegistry registry) {
        Path dir;
        try {
            dir = FixtureFiles.locate(FIXTURES, CorpusVersion.compute(Path.of("corpus")),
                    "ollama/nomic-embed-text", CHUNKER);
        } catch (RuntimeException e) {
            throw new IllegalStateException("run scripts/build-fixture.sh: " + e.getMessage(), e);
        }
        registry.add("rag.embedding.fixture-dir", dir::toString);
        registry.add("rag.embedding.cache-dir", () -> "build/ci-cache");
        registry.add("rag.corpus.root", () -> "corpus");
        registry.add("rag.chunking.kind", () -> "structural");
        registry.add("rag.chunking.structural.max-tokens", () -> "400");
        registry.add("rag.env-prefix", () -> "t_ci");
    }

    @Autowired IngestCommand ingest;

    @BeforeEach
    void ingestExampleBank() {
        ingest.run(new DefaultApplicationArguments("ingest", "--scope=example-bank", "--chunker=structural",
                "--promote", "--recreate"));
    }

    @Test
    void gateMatchesTheBaseline() {
        Map<Clearance, String> keys = Map.of(Clearance.RESTRICTED, KEY, Clearance.INTERNAL, INTERNAL_KEY,
                Clearance.PUBLIC, PUBLIC_KEY);
        boolean writeBaseline = Boolean.parseBoolean(System.getProperty("ci.writeBaseline", "false"));
        var result = CiGate.run(URI.create("http://127.0.0.1:" + port), keys, Path.of("eval/golden-set.yaml"),
                Path.of("corpus"), Path.of("eval/baseline.json"), writeBaseline, System.out);

        assertThat(result.scored()).isEqualTo(24);
        assertThat(result.unanswerable()).isEqualTo(6);
        assertThat(result.excludedIds()).hasSize(12);
    }
}
