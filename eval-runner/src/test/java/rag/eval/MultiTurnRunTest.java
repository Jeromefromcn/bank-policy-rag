package rag.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import rag.core.eval.ExpectedSource;
import rag.core.model.Clearance;
import tools.jackson.databind.json.JsonMapper;

class MultiTurnRunTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String FOLLOW_UP = "And that one?";
    static final String REWRITTEN = "Rewritten standalone query";

    static MultiTurnItem item(String id, String split) {
        return new MultiTurnItem(id, split, List.of("g1"), FOLLOW_UP, "g2", List.of(new ExpectedSource("eb-a", "1")),
                Clearance.INTERNAL);
    }

    static final String HIT_A = """
            {"queryId":"q","hits":[{"chunkId":"c1","docId":"eb-a","sectionPath":["1"],"coveredSections":["1"],"text":"t"}],
             "diagnostics":{"bestVectorSimilarity":0.8}}""";
    static final String HIT_B = """
            {"queryId":"q","hits":[{"chunkId":"c2","docId":"eb-b","sectionPath":["2"],"coveredSections":["2"],"text":"t"}],
             "diagnostics":{"bestVectorSimilarity":0.8}}""";

    static String ask(String body, String rewritten) {
        return """
                {"answerId":"a_1","status":"ANSWERED","refusalReason":null,"answer":"An answer [1].","citations":[],
                 "usage":{"promptTokens":200,"completionTokens":20},
                 "latencyMs":{"retrieval":5,"rerank":0,"generation":40,"rewrite":%d},
                 "conversationId":"cv_1","rewrittenQuery":%s}""".formatted(rewritten == null ? 0 : 30,
                rewritten == null ? "null" : "\"" + rewritten + "\"");
    }

    static StubServer stub() throws Exception {
        var stub = new StubServer();
        stub.searchResponder = body -> {
            String q = JSON.readTree(body).get("query").asString();
            return q.equals(FOLLOW_UP) ? HIT_B : HIT_A;
        };
        stub.askResponder = body -> JSON.readTree(body).path("conversationId").isMissingNode() ? ask(body, null)
                : ask(body, REWRITTEN);
        return stub;
    }

    @Test
    void scoresRawRewriteAndStandalone() throws Exception {
        try (var stub = stub()) {
            var out = MultiTurnRun.run(List.of(item("m1", "dev")), "dev", MultiTurnSetTest.GOLDEN,
                    KeySet.fromEnv(KeySetTest.ENV), new MultiTurnRun.Config(stub.base(), "HYBRID", false, 10));
            assertThat(out).hasSize(1);
            var o = out.getFirst();
            assertThat(o.error()).isNull();
            assertThat(o.raw().recall5()).isEqualTo(0.0);
            assertThat(o.rewrite().recall5()).isEqualTo(1.0);
            assertThat(o.standalone().recall5()).isEqualTo(1.0);
            assertThat(o.rewrittenQuery()).isEqualTo(REWRITTEN);
            assertThat(o.rewriteMs()).isEqualTo(30);

            var asks = stub.requests.stream().filter(r -> r.path().equals("/v1/ask")).toList();
            assertThat(asks).hasSize(2);
            assertThat(JSON.readTree(asks.get(0).body()).get("question").asString()).isEqualTo("Turn one question?");
            assertThat(JSON.readTree(asks.get(0).body()).has("conversationId")).isFalse();
            assertThat(JSON.readTree(asks.get(1).body()).get("conversationId").asString()).isEqualTo("cv_1");
            assertThat(stub.requests).allSatisfy(r -> assertThat(r.apiKey()).isEqualTo("k-int"));
            var searched = stub.requests.stream().filter(r -> r.path().equals("/v1/search"))
                    .map(r -> JSON.readTree(r.body()).get("query").asString()).toList();
            assertThat(searched).containsExactlyInAnyOrder(FOLLOW_UP, REWRITTEN, "Standalone question?");
        }
    }

    @Test
    void filtersBySplit() throws Exception {
        try (var stub = stub()) {
            var out = MultiTurnRun.run(List.of(item("m1", "dev"), item("m2", "test")), "test", MultiTurnSetTest.GOLDEN,
                    KeySet.fromEnv(KeySetTest.ENV), new MultiTurnRun.Config(stub.base(), "HYBRID", false, 10));
            assertThat(out).extracting(o -> o.item().id()).containsExactly("m2");
        }
    }

    @Test
    void askFailureIsRecordedAndRunContinues() throws Exception {
        try (var stub = stub()) {
            stub.askStatus = 503;
            stub.askResponder = body -> "{\"type\":\"urn:rag:problem:generation-unavailable\"}";
            var out = MultiTurnRun.run(List.of(item("m1", "dev"), item("m2", "dev")), "dev", MultiTurnSetTest.GOLDEN,
                    KeySet.fromEnv(KeySetTest.ENV), new MultiTurnRun.Config(stub.base(), "HYBRID", false, 10));
            assertThat(out).hasSize(2);
            assertThat(out.getFirst().error()).contains("503");
            assertThat(out.getFirst().rewrite()).isNull();
            assertThat(out.getFirst().raw().recall5()).isEqualTo(0.0);
            assertThat(out.getFirst().standalone().recall5()).isEqualTo(1.0);
        }
    }
}
