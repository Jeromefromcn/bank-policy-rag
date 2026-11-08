package rag.app.ask;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import rag.app.es.ChunkIndexAdmin;
import rag.app.es.ChunkWriter;
import rag.app.support.Http;
import rag.app.support.IntegrationTest;
import rag.app.support.StubChatModel;
import rag.app.support.TestIndex;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@TestPropertySource(properties = "rag.env-prefix=t_ask")
class AskControllerIT extends IntegrationTest {

    static final String QUERY = "tolerance for disruption";
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired ChunkIndexAdmin admin;
    @Autowired ChunkWriter writer;
    @Autowired StubChatModel stub;

    @BeforeEach
    void setUp() {
        stub.reset();
        if (admin.aliasInfo("t_ask-chunks").isEmpty()) {
            TestIndex.rebuild(admin, writer, "t_ask", TestIndex.mixed(QUERY));
        }
    }

    JsonNode ask(String key, Map<String, Object> body, int expectedStatus) {
        var r = Http.post(port, "/v1/ask", key, JSON.writeValueAsString(body));
        assertThat(r.status()).as(r.body()).isEqualTo(expectedStatus);
        return JSON.readTree(r.body());
    }

    @Test
    void answersWithNumberedSources() {
        JsonNode r = ask(KEY, Map.of("question", QUERY), 200);
        assertThat(r.get("status").asString()).isEqualTo("ANSWERED");
        assertThat(r.get("answerId").asString()).startsWith("a_");
        assertThat(r.get("answer").asString()).isEqualTo("Answer [1].");
        JsonNode citations = r.get("citations");
        assertThat(citations.size()).isEqualTo(5);
        for (int i = 0; i < 5; i++) {
            assertThat(citations.get(i).get("ref").asInt()).isEqualTo(i + 1);
        }
        assertThat(citations.get(0).get("chunkId").asString()).isEqualTo("c-int");
        assertThat(citations.get(0).get("quote").asString()).isEqualTo(QUERY);
        assertThat(r.get("usage").get("promptTokens").asInt()).isEqualTo(100);
        assertThat(r.get("usage").get("completionTokens").asInt()).isEqualTo(10);
        assertThat(r.get("latencyMs").get("rerank").asLong()).isZero();
        assertThat(r.get("latencyMs").has("retrieval")).isTrue();
        assertThat(stub.calls()).isEqualTo(1);
    }

    @Test
    void publicCallerPromptHasNoInternalSources() {
        ask(PUBLIC_KEY, Map.of("question", QUERY), 200);
        String user = stub.lastPrompt().getInstructions().stream()
                .filter(m -> m.getMessageType() == MessageType.USER).findFirst().orElseThrow().getText();
        assertThat(user).doesNotContain("eb-a").contains("spm-x");
    }

    @Test
    void zeroHitsSkipsLlm() {
        admin.delete(admin.aliasInfo("t_ask-chunks").orElseThrow().index());
        JsonNode r = ask(KEY, Map.of("question", QUERY), 200);
        assertThat(r.get("status").asString()).isEqualTo("ANSWERED");
        assertThat(r.get("answer").asString()).isEqualTo("No sources were found for this question.");
        assertThat(r.get("citations").size()).isZero();
        assertThat(r.get("latencyMs").get("generation").asLong()).isZero();
        assertThat(stub.calls()).isZero();
    }

    @Test
    void conversationIdRejected() {
        JsonNode r = ask(KEY, Map.of("question", QUERY, "conversationId", "c1"), 400);
        assertThat(r.get("type").asString()).isEqualTo("urn:rag:problem:invalid-request");
    }

    @Test
    void blankQuestionRejected() {
        ask(KEY, Map.of("question", " "), 400);
    }

    @Test
    void unknownModeRejectedAndHybridAccepted() {
        assertThat(ask(KEY, Map.of("question", QUERY, "mode", "FUZZY"), 400).get("type").asString())
                .isEqualTo("urn:rag:problem:unsupported-mode");
        assertThat(ask(KEY, Map.of("question", QUERY, "mode", "HYBRID"), 200).get("status").asString()).isEqualTo("ANSWERED");
        assertThat(ask(KEY, Map.of("question", QUERY, "mode", "BM25"), 200).get("status").asString()).isEqualTo("ANSWERED");
    }

    @Test
    void llmFailureReturns503() {
        stub.failNext();
        assertThat(ask(KEY, Map.of("question", QUERY), 503).get("type").asString())
                .isEqualTo("urn:rag:problem:generation-unavailable");
    }

    @Test
    void requiresKey() {
        assertThat(Http.post(port, "/v1/ask", null, "{\"question\":\"x\"}").status()).isEqualTo(401);
    }
}
