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
import rag.app.support.TestIndex;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Multi-turn /v1/ask (spec 04 §6): conversations bound to the caller, rewrite before retrieval. */
@TestPropertySource(properties = "rag.env-prefix=t_askconv")
class AskConversationIT extends IntegrationTest {

    static final String QUERY = "tolerance for disruption";
    static final String REWRITE = "board approval of tolerance for disruption";
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired ChunkIndexAdmin admin;
    @Autowired ChunkWriter writer;
    @Autowired StubChatModel chat;

    @BeforeEach
    void setUp() {
        chat.reset();
        if (admin.aliasInfo("t_askconv-chunks").isEmpty()) {
            TestIndex.rebuild(admin, writer, "t_askconv", TestIndex.mixed(QUERY));
        }
    }

    Http.Response post(String key, Map<String, Object> body) {
        return Http.post(port, "/v1/ask", key, JSON.writeValueAsString(body));
    }

    JsonNode ask(String key, Map<String, Object> body, int expectedStatus) {
        var r = post(key, body);
        assertThat(r.status()).as(r.body()).isEqualTo(expectedStatus);
        return JSON.readTree(r.body());
    }

    String firstTurn() {
        JsonNode r = ask(KEY, Map.of("question", QUERY), 200);
        chat.reset();
        return r.get("conversationId").asString();
    }

    String userText(int call) {
        return chat.prompts().get(call).getUserMessage().getText();
    }

    @Test
    void firstTurnCreatesConversationAndMakesOneModelCall() {
        JsonNode r = ask(KEY, Map.of("question", QUERY), 200);
        assertThat(r.get("conversationId").asString()).startsWith("cv_");
        assertThat(r.get("rewrittenQuery").isNull()).isTrue();
        assertThat(r.get("latencyMs").get("rewrite").asLong()).isZero();
        assertThat(chat.calls()).isEqualTo(1);
    }

    @Test
    void followUpRewritesAndRetrievesWithRewrittenQuery() {
        String id = firstTurn();
        chat.replies(REWRITE, "Follow-up answer [1].");
        JsonNode r = ask(KEY, Map.of("question", "and who approves it?", "conversationId", id), 200);

        assertThat(chat.calls()).isEqualTo(2);
        assertThat(r.get("conversationId").asString()).isEqualTo(id);
        assertThat(r.get("rewrittenQuery").asString()).isEqualTo(REWRITE);
        assertThat(r.get("answer").asString()).isEqualTo("Follow-up answer [1].");
        assertThat(userText(0)).contains("<<<TURN 1>>>").contains(QUERY).contains("and who approves it?");
        String answerPrompt = userText(1);
        assertThat(answerPrompt).contains("Question: " + REWRITE).doesNotContain("<<<TURN").doesNotContain("who approves it");
    }

    @Test
    void foreignConversationIdIs404LikeUnknown() {
        String id = firstTurn();
        var foreign = post(PUBLIC_KEY, Map.of("question", "follow-up", "conversationId", id));
        var unknown = post(PUBLIC_KEY, Map.of("question", "follow-up", "conversationId", "cv_unknown"));
        assertThat(foreign.status()).isEqualTo(404);
        assertThat(foreign.body()).isEqualTo(unknown.body());
        assertThat(chat.calls()).isZero();
    }

    @Test
    void rewriteFailureIs503AndStoresNothing() {
        String id = firstTurn();
        chat.failNext();
        JsonNode failed = ask(KEY, Map.of("question", "lost follow-up", "conversationId", id), 503);
        assertThat(failed.get("type").asString()).isEqualTo("urn:rag:problem:generation-unavailable");

        chat.replies(REWRITE, "Answer [1].");
        ask(KEY, Map.of("question", "next follow-up", "conversationId", id), 200);
        String rewritePrompt = userText(1); // call 0 was the failed rewrite
        assertThat(rewritePrompt).contains("<<<TURN 1>>>").doesNotContain("<<<TURN 2>>>").doesNotContain("lost follow-up");
    }

    @Test
    void refusedTurnIsStoredWithoutAnswer() {
        chat.reply("NO_ANSWER");
        JsonNode first = ask(KEY, Map.of("question", QUERY), 200);
        assertThat(first.get("status").asString()).isEqualTo("REFUSED");
        String id = first.get("conversationId").asString();
        chat.reset();

        chat.replies(REWRITE);
        ask(KEY, Map.of("question", "follow-up", "conversationId", id), 200);
        assertThat(userText(0)).contains("<<<TURN 1>>>").contains(QUERY).contains("(no answer)").doesNotContain("NO_ANSWER");
    }

    @Test
    void usageSumsRewriteAndAnswer() {
        String id = firstTurn();
        chat.replies(REWRITE);
        JsonNode r = ask(KEY, Map.of("question", "follow-up", "conversationId", id), 200);
        assertThat(r.get("usage").get("promptTokens").asInt()).isEqualTo(200);
        assertThat(r.get("usage").get("completionTokens").asInt()).isEqualTo(20);
        assertThat(r.get("latencyMs").has("rewrite")).isTrue();
    }

    @Test
    void fallbackRewriteUsesOriginalQuestion() {
        String id = firstTurn();
        chat.replies("line one\nline two");
        JsonNode r = ask(KEY, Map.of("question", QUERY, "conversationId", id), 200);
        assertThat(r.get("rewrittenQuery").asString()).isEqualTo(QUERY);
        assertThat(userText(1)).contains("Question: " + QUERY);
    }
}
