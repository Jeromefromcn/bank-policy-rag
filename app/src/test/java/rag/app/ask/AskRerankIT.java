package rag.app.ask;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import rag.app.es.ChunkIndexAdmin;
import rag.app.es.ChunkWriter;
import rag.app.support.Http;
import rag.app.support.IntegrationTest;
import rag.app.support.StubChatModel;
import rag.app.support.StubReranker;
import rag.app.support.TestIndex;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Rerank on /v1/ask and the rerank-score gate (spec 04 §4, §5): gate at probability 0.9. */
@TestPropertySource(properties = {"rag.env-prefix=t_askrerank", "rag.ask.min-rerank-probability=0.9"})
class AskRerankIT extends IntegrationTest {

    static final String QUERY = "tolerance for disruption";
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired ChunkIndexAdmin admin;
    @Autowired ChunkWriter writer;
    @Autowired StubChatModel chat;
    @Autowired StubReranker reranker;

    @BeforeEach
    void setUp() {
        chat.reset();
        reranker.reset();
        if (admin.aliasInfo("t_askrerank-chunks").isEmpty()) {
            TestIndex.rebuild(admin, writer, "t_askrerank", TestIndex.mixed(QUERY));
        }
    }

    JsonNode ask(Map<String, Object> body) {
        var r = Http.post(port, "/v1/ask", KEY, JSON.writeValueAsString(body));
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    @Test
    void rerankGateRefusesWithoutCallingModel() {
        reranker.scoreWith((q, p) -> 0.0); // probability 0.5 < 0.9
        JsonNode r = ask(Map.of("question", QUERY, "rerank", true));
        assertThat(r.get("status").asString()).isEqualTo("REFUSED");
        assertThat(r.get("refusalReason").asString()).isEqualTo("LOW_RETRIEVAL_SCORE");
        assertThat(chat.calls()).isZero();
        assertThat(reranker.calls()).hasSize(1);
    }

    @Test
    void rerankGateIgnoredWhenRerankFalse() {
        JsonNode r = ask(Map.of("question", QUERY, "rerank", false));
        assertThat(r.get("status").asString()).isEqualTo("ANSWERED");
        assertThat(chat.calls()).isEqualTo(1);
        assertThat(reranker.calls()).isEmpty();
    }

    @Test
    void answerPromptUsesRerankedOrder() {
        reranker.scoreWith((q, p) -> p.equals("outsourcing agreements") ? 9.0 : 3.0);
        JsonNode r = ask(Map.of("question", QUERY, "rerank", true));
        assertThat(r.get("status").asString()).as(r.toString()).isEqualTo("ANSWERED");
        String prompt = chat.lastPrompt().getContents();
        int source1 = prompt.indexOf("<<<SOURCE 1 ");
        int source2 = prompt.indexOf("<<<SOURCE 2 ");
        int top = prompt.indexOf("outsourcing agreements");
        assertThat(source1).isNotNegative();
        assertThat(top).isBetween(source1, source2);
        assertThat(r.get("citations").get(0).get("chunkId").asString()).isEqualTo("c-p6");
    }

    @Test
    void emptyHitsRefuseWithoutReranker() {
        JsonNode r = ask(Map.of("question", "zebra quantum harvest", "mode", "BM25", "rerank", true));
        assertThat(r.get("status").asString()).isEqualTo("REFUSED");
        assertThat(r.get("refusalReason").asString()).isEqualTo("LOW_RETRIEVAL_SCORE");
        assertThat(reranker.calls()).isEmpty();
        assertThat(chat.calls()).isZero();
    }

    @Test
    void latencyIncludesRerankStage() {
        reranker.scoreWith((q, p) -> 5.0);
        JsonNode on = ask(Map.of("question", QUERY, "rerank", true));
        assertThat(on.get("latencyMs").has("rerank")).isTrue();
        assertThat(on.get("latencyMs").get("rerank").asLong()).isNotNegative();
        JsonNode off = ask(Map.of("question", QUERY));
        assertThat(off.get("latencyMs").get("rerank").asLong()).isZero();
    }

    @Test
    void rerankerDownIs503() {
        reranker.failNext();
        var r = Http.post(port, "/v1/ask", KEY, JSON.writeValueAsString(Map.of("question", QUERY, "rerank", true)));
        assertThat(r.status()).isEqualTo(503);
        assertThat(JSON.readTree(r.body()).get("type").asString()).isEqualTo("urn:rag:problem:reranker-unavailable");
        assertThat(chat.calls()).isZero();
    }
}
