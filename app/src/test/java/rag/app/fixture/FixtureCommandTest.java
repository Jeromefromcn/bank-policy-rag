package rag.app.fixture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import rag.app.support.TestBeans;
import rag.core.corpus.CorpusVersion;
import rag.core.corpus.TestCorpus;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(TestBeans.class)
class FixtureCommandTest {

    static final Path TMP;
    static final Path CORPUS;
    static final Path GOLDEN;

    static {
        try {
            TMP = Files.createTempDirectory("fixture-command");
            CORPUS = TestCorpus.create(TMP);
            GOLDEN = TMP.resolve("golden.yaml");
            Files.writeString(GOLDEN, """
                    - id: g1
                      question: "self made question"
                      category: factual
                      expectedSources: [{docId: eb-a, section: "1"}]
                      expectedPoints: ["a point"]
                      minClearance: internal
                      split: dev
                    - id: g2
                      question: "self made unanswerable"
                      category: unanswerable
                      expectedSources: []
                      expectedPoints: []
                      minClearance: restricted
                      split: dev
                    """);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("rag.corpus.root", CORPUS::toString);
        registry.add("rag.embedding.cache-dir", () -> TMP.resolve("cache").toString());
    }

    @Autowired FixtureCommand command;

    @AfterAll
    static void cleanUp() throws IOException {
        try (var walk = Files.walk(TMP)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Test
    void writesVectorOnlyFixturesForExampleBankScoredItems() throws IOException {
        Path out = TMP.resolve("fixtures");
        command.run(new DefaultApplicationArguments("fixture", "--out=" + out, "--golden=" + GOLDEN));

        Path dir = FixtureFiles.locate(out, CorpusVersion.compute(CORPUS), "fake/hash", "fixed-512-64-cl100k");
        assertThat(FixtureFiles.readVectors(dir, "chunks.jsonl")).isNotEmpty();
        assertThat(FixtureFiles.readVectors(dir, "questions.jsonl")).hasSize(1);

        String files = Files.readString(dir.resolve("chunks.jsonl"))
                + Files.readString(dir.resolve("questions.jsonl"));
        assertThat(files).doesNotContain("Self-made text", "self made question");
    }
}
