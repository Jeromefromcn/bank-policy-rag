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

/** No reader credentials: the read endpoint must say so, not fall back to the write-only user. */
@TestPropertySource(properties = {"rag.env-prefix=t_read2", "rag.audit.index=t_read2-audit-v1"})
class AuditReaderUnavailableIT extends IntegrationTest {

    static final String QUERY = "tolerance for disruption";
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired ChunkIndexAdmin admin;
    @Autowired ChunkWriter writer;
    @Autowired StubChatModel stub;

    @BeforeEach
    void setUp() {
        stub.reset();
        TestIndex.rebuild(admin, writer, "t_read2", TestIndex.mixed(QUERY));
    }

    @Test
    void readerNotConfiguredIs503() {
        var ask = Http.post(port, "/v1/ask", KEY, JSON.writeValueAsString(Map.of("question", QUERY)));
        assertThat(ask.status()).as(ask.body()).isEqualTo(200);
        String id = JSON.readTree(ask.body()).get("answerId").asString();

        var r = Http.get(port, "/v1/audit/" + id, ADMIN_KEY);
        assertThat(r.status()).isEqualTo(503);
        JsonNode body = JSON.readTree(r.body());
        assertThat(body.get("type").asString()).isEqualTo("urn:rag:problem:audit-reader-unavailable");
    }
}
