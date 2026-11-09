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

    static final String GEN_GOLDEN = """
            - id: g1
              question: "Question one?"
              category: factual
              expectedSources: [{docId: eb-a, section: "1"}]
              expectedPoints: ["p1", "p2"]
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
              question: "Restricted?"
              category: restricted
              expectedSources: [{docId: eb-a, section: "1"}]
              expectedPoints: ["p3"]
              minClearance: internal
              split: dev
            """;

    static final Map<String, String> GEN_ENV = Map.of("RAG_KEY_DEV", "k-res", "RAG_KEY_INTERNAL", "k-int", "RAG_KEY_PUBLIC", "k-pub",
            "DEEPSEEK_API_KEY", "judge-key");

    /** Judge stub: "yes" for every point or item in the INPUT section of the prompt. */
    static String allYes(String requestBody) {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        String content = mapper.readTree(requestBody).get("messages").get(0).get("content").asString();
        var input = mapper.readTree(content.substring(content.indexOf("INPUT:\n") + "INPUT:\n".length()));
        int n = input.has("points") ? input.get("points").size() : input.get("items").size();
        return StubServer.judgeContent(mapper.writeValueAsString(Map.of("verdicts", java.util.Collections.nCopies(n, "yes"))));
    }

    Path runGeneration(StubServer stub) throws Exception {
        Path corpus = TestCorpus.create(tmp);
        Path golden = Files.writeString(tmp.resolve("golden.yaml"), GEN_GOLDEN);
        stub.judgeResponder = EvalRunnerTest::allYes;
        stub.askBody = stub.askBody.replace("An answer [1].", "DISTINCTIVE_ANSWER_TEXT [1].");
        stub.searchBody = stub.searchBody.replace("\"text\":\"t\"", "\"text\":\"DISTINCTIVE_SOURCE_TEXT\"");
        var out = new ByteArrayOutputStream();
        int code = EvalRunner.run(new String[] {"--base-url=" + stub.base(), "--golden=" + golden, "--corpus=" + corpus,
                "--out=" + tmp.resolve("runs"), "--generation=true", "--split=dev", "--judge-url=" + stub.base(),
                "--judge-cache=" + tmp.resolve("cache")}, GEN_ENV, new PrintStream(out));
        assertThat(code).as(out.toString()).isZero();
        try (var dirs = Files.list(tmp.resolve("runs"))) {
            return dirs.findFirst().orElseThrow();
        }
    }

    @Test
    void generationReportContainsNoSourceOrAnswerText() throws Exception {
        try (var stub = new StubServer()) {
            Path dir = runGeneration(stub);

            assertThat(dir.getFileName().toString()).endsWith("-generation");
            for (String file : new String[] {"report.md", "report.json"}) {
                assertThat(Files.readString(dir.resolve(file))).doesNotContain("DISTINCTIVE_ANSWER_TEXT").doesNotContain("DISTINCTIVE_SOURCE_TEXT");
            }
            assertThat(Files.readString(dir.resolve("answers.json"))).contains("DISTINCTIVE_ANSWER_TEXT");
            assertThat(Files.readString(dir.resolve("report.md"))).contains("gen-model").contains("faithfulness");
        }
    }

    @Test
    void restrictedItemsRunTwiceWithBothKeys() throws Exception {
        try (var stub = new StubServer()) {
            runGeneration(stub);

            assertThat(stub.requests.stream().filter(r -> r.path().equals("/v1/ask")).map(StubServer.Request::apiKey).toList())
                    .containsExactly("k-int", "k-res", "k-int", "k-pub");
        }
    }

    @Test
    void generationRequiresJudgeKey() throws Exception {
        try (var stub = new StubServer()) {
            assertThat(run(stub, Map.of("RAG_KEY_DEV", "k"), "--generation=true")).isEqualTo(2);
        }
    }

    @Test
    void missingKeysFailBeforeAnyCallIsMade() throws Exception {
        try (var stub = new StubServer()) {
            Path corpus = TestCorpus.create(tmp);
            Path golden = Files.writeString(tmp.resolve("golden.yaml"), GEN_GOLDEN);
            var out = new ByteArrayOutputStream();
            int code = EvalRunner.run(new String[] {"--base-url=" + stub.base(), "--golden=" + golden, "--corpus=" + corpus,
                    "--out=" + tmp.resolve("runs"), "--generation=true", "--split=dev", "--judge-url=" + stub.base()},
                    Map.of("RAG_KEY_DEV", "k-res", "DEEPSEEK_API_KEY", "j"), new PrintStream(out));

            assertThat(code).isEqualTo(1);
            assertThat(out.toString()).contains("RAG_KEY_INTERNAL").contains("RAG_KEY_PUBLIC");
            assertThat(stub.requests).extracting(StubServer.Request::path).doesNotContain("/v1/ask", "/chat/completions");
        }
    }

    @Test
    void sourceRecoveryFailureRecordsErrorsForPointsAndSentences() throws Exception {
        try (var stub = new StubServer()) {
            stub.searchBody = stub.searchBody.replace("\"c1\"", "\"zz1\"");
            Path dir = runGeneration(stub);

            String answers = Files.readString(dir.resolve("answers.json"));
            assertThat(answers).contains("\"ERROR\"").doesNotContain("\"SUPPORTED\"");
            var g1 = tools.jackson.databind.json.JsonMapper.builder().build().readTree(answers).get("items").get(0);
            assertThat(g1.get("sentences").size()).isGreaterThan(0);
            assertThat(g1.get("sentences").get(0).get("verdict").asString()).isEqualTo("ERROR");
        }
    }

    Path onlyRunDir() throws Exception {
        try (var s = Files.list(tmp.resolve("runs"))) {
            return s.findFirst().orElseThrow();
        }
    }

    @Test
    void repeatScoresFirstRepetitionOnly() throws Exception {
        try (var stub = new StubServer()) {
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            stub.searchResponder = body -> calls.getAndIncrement() == 0 ? """
                    {"queryId":"q","hits":[{"chunkId":"c1","docId":"eb-a","sectionPath":["1"],"coveredSections":["1"],"text":"t"}],"diagnostics":{}}"""
                    : """
                    {"queryId":"q","hits":[{"chunkId":"c9","docId":"eb-z","sectionPath":["9"],"coveredSections":["9"],"text":"t"}],"diagnostics":{}}""";
            assertThat(run(stub, Map.of("RAG_KEY_DEV", "k"), "--split=dev", "--repeat=3")).isZero();
            assertThat(stub.searches()).isEqualTo(3);
            var json = tools.jackson.databind.json.JsonMapper.builder().build().readTree(Files.readString(onlyRunDir().resolve("report.json")));
            assertThat(json.get("items").get(0).get("scores").get("recall5").asDouble()).isEqualTo(1.0);
            assertThat(json.get("latencyStages").get("total").get("all").get("n").asInt()).isEqualTo(3);
        }
    }

    @Test
    void rerankFlagIsSentAndRecorded() throws Exception {
        try (var stub = new StubServer()) {
            assertThat(run(stub, Map.of("RAG_KEY_DEV", "k"), "--split=dev", "--rerank=true", "--reranker-model=cross-encoder/x")).isZero();
            assertThat(stub.requests.stream().filter(r -> r.path().equals("/v1/search")))
                    .allSatisfy(r -> assertThat(r.body()).contains("\"rerank\":true"));
            String md = Files.readString(onlyRunDir().resolve("report.md"));
            assertThat(md).contains("| rerank | true |").contains("| rerankerModel | cross-encoder/x |");
        }
    }

    @Test
    void invalidRepeatExits2() throws Exception {
        try (var stub = new StubServer()) {
            assertThat(run(stub, Map.of("RAG_KEY_DEV", "k"), "--repeat=0")).isEqualTo(2);
            assertThat(stub.searches()).isZero();
        }
    }
}
