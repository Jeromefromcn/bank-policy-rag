package rag.app.audit;

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

@TestPropertySource(properties = {"rag.env-prefix=t_read", "rag.audit.index=t_read-audit-v1",
        "rag.audit.reader-username=elastic", "rag.audit.reader-password=test-password"})
class AuditReadIT extends IntegrationTest {

    static final String QUERY = "tolerance for disruption";
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired ChunkIndexAdmin admin;
    @Autowired ChunkWriter writer;
    @Autowired StubChatModel stub;

    @BeforeEach
    void setUp() {
        stub.reset();
        TestIndex.rebuild(admin, writer, "t_read", TestIndex.mixed(QUERY));
    }

    String answerId() {
        var r = Http.post(port, "/v1/ask", KEY, JSON.writeValueAsString(Map.of("question", QUERY)));
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body()).get("answerId").asString();
    }

    String queryId() {
        var r = Http.post(port, "/v1/search", KEY, JSON.writeValueAsString(Map.of("query", QUERY)));
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body()).get("queryId").asString();
    }

    @Test
    void adminReadsAnAnswerRecord() {
        String id = answerId();
        var r = Http.get(port, "/v1/audit/" + id, ADMIN_KEY);
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        JsonNode body = JSON.readTree(r.body());
        assertThat(body.get("answerId").asString()).isEqualTo(id);
        assertThat(body.get("renderedPrompt").asString()).contains("[system]", "[user]");
        assertThat(body.get("retrieval").get("hits").size()).isGreaterThan(0);
    }

    @Test
    void adminReadsASearchRecordByQueryId() {
        String id = queryId();
        var r = Http.get(port, "/v1/audit/" + id, ADMIN_KEY);
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(JSON.readTree(r.body()).get("status").asString()).isEqualTo("OK");
    }

    @Test
    void nonAdminIs403AndUnknownIs404() {
        String id = answerId();
        assertThat(Http.get(port, "/v1/audit/" + id, KEY).status()).isEqualTo(403);
        assertThat(Http.get(port, "/v1/audit/" + id, PUBLIC_KEY).status()).isEqualTo(403);
        assertThat(Http.get(port, "/v1/audit/a_unknown", ADMIN_KEY).status()).isEqualTo(404);
    }
}
