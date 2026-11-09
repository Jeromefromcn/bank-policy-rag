package rag.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rag.core.corpus.TestCorpus;

class EvalRunnerTest {

    @TempDir Path tmp;

    static final String GOLDEN = """
            - id: g1
              question: "Question one?"
              category: factual
              expectedSources: [{docId: eb-a, section: "1"}]
              expectedPoints: []
              minClearance: internal
              split: dev
            - id: g2
              question: "Unanswerable?"
              category: unanswerable
              expectedSources: []
              expectedPoints: []
              minClearance: public
              split: dev
            - id: g3
              question: "Question three?"
              category: factual
              expectedSources: [{docId: adv-x, section: "1"}]
              expectedPoints: []
              minClearance: public
              split: test
            """;

    int run(StubServer stub, Map<String, String> env, String... extra) throws Exception {
        Path corpus = TestCorpus.create(tmp);
        Path golden = Files.writeString(tmp.resolve("golden.yaml"), GOLDEN);
        String[] base = {"--base-url=" + stub.base(), "--golden=" + golden, "--corpus=" + corpus, "--out=" + tmp.resolve("runs")};
        String[] args = new String[base.length + extra.length];
        System.arraycopy(base, 0, args, 0, base.length);
        System.arraycopy(extra, 0, args, base.length, extra.length);
        return EvalRunner.run(args, env, new PrintStream(new ByteArrayOutputStream()));
    }

    @Test
    void skipsUnanswerableAndFiltersSplit() throws Exception {
        try (var stub = new StubServer()) {
            assertThat(run(stub, Map.of("RAG_KEY_DEV", "k"), "--split=dev")).isZero();
            assertThat(stub.searches()).isEqualTo(1);
            Path runDir;
            try (var s = Files.list(tmp.resolve("runs"))) {
                runDir = s.findFirst().orElseThrow();
            }
            String json = Files.readString(runDir.resolve("report.json"));
            assertThat(json).contains("\"g1\"").doesNotContain("\"g2\"").doesNotContain("\"g3\"").containsPattern("\"skipped\"\\s*:\\s*1");
            assertThat(Files.readString(runDir.resolve("report.md"))).contains("abc123");
        }
    }

    @Test
    void allSplitRunsEveryAnswerableItem() throws Exception {
        try (var stub = new StubServer()) {
            assertThat(run(stub, Map.of("RAG_KEY_DEV", "k"))).isZero();
            assertThat(stub.searches()).isEqualTo(2);
        }
    }

    @Test
    void missingIndexVersionsFailBeforeSearching() throws Exception {
        try (var stub = new StubServer()) {
            stub.infoBody = "{\"rag\":{}}";
            assertThat(run(stub, Map.of("RAG_KEY_DEV", "k"))).isEqualTo(1);
            assertThat(stub.searches()).isZero();
            assertThat(Files.exists(tmp.resolve("runs"))).isFalse();
        }
    }

    @Test
    void missingKeyExits2() throws Exception {
        try (var stub = new StubServer()) {
            assertThat(run(stub, Map.of())).isEqualTo(2);
            assertThat(stub.searches()).isZero();
        }
    }

    @Test
    void missingBaseUrlExits2() {
        assertThat(EvalRunner.run(new String[] {}, Map.of("RAG_KEY_DEV", "k"), new PrintStream(new ByteArrayOutputStream()))).isEqualTo(2);
    }

    @Test
    void modeDefaultsToHybridAndIsRecorded() throws Exception {
        try (var stub = new StubServer()) {
            assertThat(run(stub, Map.of("RAG_KEY_DEV", "k"))).isZero();
            assertThat(stub.requests.stream().filter(r -> r.path().equals("/v1/search")).map(StubServer.Request::body))
                    .allSatisfy(b -> assertThat(b).contains("\"mode\":\"HYBRID\""));
            Path runDir;
            try (var s = Files.list(tmp.resolve("runs"))) {
                runDir = s.findFirst().orElseThrow();
            }
            assertThat(Files.readString(runDir.resolve("report.md"))).contains("| mode | HYBRID |");
            assertThat(Files.readString(runDir.resolve("report.json"))).containsPattern("\"mode\"\\s*:\\s*\"HYBRID\"");
        }
    }

    @Test
    void explicitModeIsSent() throws Exception {
        try (var stub = new StubServer()) {
            assertThat(run(stub, Map.of("RAG_KEY_DEV", "k"), "--mode=BM25")).isZero();
            assertThat(stub.requests.stream().filter(r -> r.path().equals("/v1/search")).map(StubServer.Request::body))
                    .isNotEmpty().allSatisfy(b -> assertThat(b).contains("\"mode\":\"BM25\""));
        }
    }

    @Test
    void invalidModeExits2BeforeAnyRequest() throws Exception {
        try (var stub = new StubServer()) {
            assertThat(run(stub, Map.of("RAG_KEY_DEV", "k"), "--mode=FUZZY")).isEqualTo(2);
            assertThat(stub.requests).isEmpty();
        }
    }

    @Test
    void sweepWritesDevOnlyTableWithoutAskingTheModel() throws Exception {
        try (var stub = new StubServer()) {
            assertThat(run(stub, KeySetTest.ENV, "--sweep=true")).isZero();

            assertThat(stub.requests).extracting(StubServer.Request::path).doesNotContain("/v1/ask");
            try (var dirs = Files.list(tmp.resolve("runs"))) {
                Path dir = dirs.findFirst().orElseThrow();
                assertThat(dir.getFileName().toString()).endsWith("-sweep");
                assertThat(Files.readString(dir.resolve("sweep.md"))).contains("| 0.50 |");
                assertThat(Files.exists(dir.resolve("sweep.json"))).isTrue();
            }
        }
    }
}
