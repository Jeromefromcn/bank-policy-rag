package rag.app.audit;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import rag.app.conversation.ConversationStore;
import rag.app.es.ChunkIndexAdmin;
import rag.app.es.ChunkWriter;
import rag.app.security.Principal;
import rag.app.support.Http;
import rag.app.support.IntegrationTest;
import rag.app.support.StubChatModel;
import rag.app.support.TestIndex;
import rag.core.model.Clearance;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@TestPropertySource(properties = {"rag.env-prefix=t_audit2", "rag.audit.index=t_audit2-audit-v1"})
class AuditIT extends IntegrationTest {

    static final String PREFIX = "t_audit2";
    static final String AUDIT_INDEX = "t_audit2-audit-v1";
    static final String QUERY = "tolerance for disruption";
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired AuditStore store;
    @Autowired AuditIndexAdmin admin;
    @Autowired ElasticsearchClient client;
    @Autowired ChunkIndexAdmin chunkAdmin;
    @Autowired ChunkWriter writer;
    @Autowired StubChatModel stub;
    @Autowired ConversationStore conversations;

    @BeforeEach
    void setUp() {
        stub.reset();
        TestIndex.rebuild(chunkAdmin, writer, PREFIX, TestIndex.mixed(QUERY));
    }

    JsonNode ask(String key, String body) {
        var r = Http.post(port, "/v1/ask", key, body);
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    JsonNode search(String key, String query) {
        var r = Http.post(port, "/v1/search", key, JSON.writeValueAsString(Map.of("query", query)));
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    AuditRecord record(String id) throws IOException {
        return client.get(g -> g.index(AUDIT_INDEX).id(id), AuditRecord.class).source();
    }

    @Test
    void answerWritesOneRecordWithTheFullPrompt() throws Exception {
        JsonNode r = ask(KEY, JSON.writeValueAsString(Map.of("question", QUERY)));
        String id = r.get("answerId").asString();
        AuditRecord rec = record(id);
        assertThat(rec.answerId()).isEqualTo(id);
        assertThat(rec.endpoint()).isEqualTo("ask");
        assertThat(rec.renderedPrompt()).contains("[system]", "[user]", QUERY);
        assertThat(rec.retrieval().hits()).isNotEmpty();
        assertThat(rec.model().id()).isEqualTo("deepseek-flash");
        assertThat(rec.answer()).isEqualTo("Answer [1].");
        assertThat(rec.internalRefusalCause()).isNull();
    }

    @Test
    void searchWritesAQueryIdRecord() throws Exception {
        JsonNode r = search(KEY, QUERY);
        String id = r.get("queryId").asString();
        AuditRecord rec = record(id);
        assertThat(rec.queryId()).isEqualTo(id);
        assertThat(rec.status()).isEqualTo("OK");
        assertThat(rec.renderedPrompt()).isNull();
        assertThat(rec.model()).isNull();
        assertThat(rec.answer()).isNull();
    }

    @Test
    void refusalRecordHasNoPromptAndTheProbeCause() throws Exception {
        TestIndex.rebuild(chunkAdmin, writer, PREFIX, TestIndex.hiddenOnly(QUERY));
        JsonNode r = ask(PUBLIC_KEY, JSON.writeValueAsString(Map.of("question", QUERY)));
        assertThat(r.get("status").asString()).isEqualTo("REFUSED");
        AuditRecord rec = record(r.get("answerId").asString());
        assertThat(rec.renderedPrompt()).isNull();
        assertThat(rec.internalRefusalCause()).isEqualTo("NO_ACCESSIBLE_SOURCES");
    }

    @Test
    void probeCauseIsAuditOnly() {
        TestIndex.rebuild(chunkAdmin, writer, PREFIX, TestIndex.hiddenOnly(QUERY));
        var hidden = Http.post(port, "/v1/ask", PUBLIC_KEY, JSON.writeValueAsString(Map.of("question", QUERY)));
        var absent = Http.post(port, "/v1/ask", PUBLIC_KEY, JSON.writeValueAsString(Map.of("question", "zebra quantum harvest")));
        assertThat(hidden.status()).isEqualTo(200);
        assertThat(absent.status()).isEqualTo(200);
        assertThat(JSON.readTree(hidden.body()).has("internalRefusalCause")).isFalse();
        assertThat(JSON.readTree(hidden.body()).get("refusalReason"))
                .isEqualTo(JSON.readTree(absent.body()).get("refusalReason"));
    }

    @Test
    void auditFailureReturns503AndStoresNoTurn() throws Exception {
        JsonNode first = ask(KEY, JSON.writeValueAsString(Map.of("question", QUERY)));
        String conversationId = first.get("conversationId").asString();
        client.indices().close(c -> c.index(AUDIT_INDEX));
        try {
            var r = Http.post(port, "/v1/ask", KEY,
                    JSON.writeValueAsString(Map.of("question", "follow-up", "conversationId", conversationId)));
            assertThat(r.status()).isEqualTo(503);
        } finally {
            client.indices().open(o -> o.index(AUDIT_INDEX));
        }
        var principal = new Principal("tester", Clearance.RESTRICTED, Set.of());
        assertThat(conversations.history(conversationId, principal).orElseThrow()).hasSize(1);
    }

    @Test
    void latencyAuditIsPresent() {
        JsonNode ask = ask(KEY, JSON.writeValueAsString(Map.of("question", QUERY)));
        JsonNode search = search(KEY, QUERY);
        assertThat(ask.get("latencyMs").get("audit").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(search.get("diagnostics").get("latencyMs").get("audit").asLong()).isGreaterThanOrEqualTo(0);
    }
}
