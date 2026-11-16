package rag.app.audit;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
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

@TestPropertySource(properties = {"rag.env-prefix=t_replay", "rag.audit.index=t_replay-audit-v1",
        "rag.audit.reader-username=elastic", "rag.audit.reader-password=test-password"})
class AuditReplayIT extends IntegrationTest {

    static final String QUERY = "tolerance for disruption";
    static final String CHUNKS = "t_replay-chunks";
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired ChunkIndexAdmin admin;
    @Autowired ChunkWriter writer;
    @Autowired StubChatModel stub;
    @Autowired ElasticsearchClient client;

    @BeforeEach
    void setUp() {
        stub.reset();
        TestIndex.rebuild(admin, writer, "t_replay", TestIndex.mixed(QUERY));
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

    JsonNode replay(String id) {
        var r = Http.post(port, "/v1/audit/" + id + "/replay", ADMIN_KEY, "{}");
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    @Test
    void answeredRecordReplaysTheRecordedPrompt() {
        String id = answerId();
        stub.replies("Replayed [1].");
        JsonNode body = replay(id);
        assertThat(body.get("modelCall").asBoolean()).isTrue();
        assertThat(body.get("replay").get("answer").asString()).isEqualTo("Replayed [1].");
        assertThat(body.get("contentHashes").get("checked").asInt())
                .isEqualTo(body.get("contentHashes").get("matched").asInt());
    }

    @Test
    void refusedRecordWithNoPromptReplaysWithoutAModelCall() {
        TestIndex.rebuild(admin, writer, "t_replay", TestIndex.hiddenOnly(QUERY));
        var ask = Http.post(port, "/v1/ask", PUBLIC_KEY, JSON.writeValueAsString(Map.of("question", QUERY)));
        assertThat(ask.status()).as(ask.body()).isEqualTo(200);
        String id = JSON.readTree(ask.body()).get("answerId").asString();
        int before = stub.calls();

        JsonNode body = replay(id);
        assertThat(body.get("modelCall").asBoolean()).isFalse();
        assertThat(body.get("replay").get("status").asString()).isEqualTo("REFUSED");
        assertThat(stub.calls()).isEqualTo(before);
    }

    @Test
    void searchRecordIs409() {
        var r = Http.post(port, "/v1/audit/" + queryId() + "/replay", ADMIN_KEY, "{}");
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.body()).contains("urn:rag:problem:replay-unsupported");
    }

    @Test
    void missingChunkIs409() throws Exception {
        String id = answerId();
        client.delete(d -> d.index(CHUNKS).id("c-int"));
        client.indices().refresh(r -> r.index(CHUNKS));
        var r = Http.post(port, "/v1/audit/" + id + "/replay", ADMIN_KEY, "{}");
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.body()).contains("urn:rag:problem:content-hash-mismatch");
    }

    @Test
    void changedChunkIs409() throws Exception {
        String id = answerId();
        client.update(u -> u.index(CHUNKS).id("c-int")
                .doc(Map.of("content", "changed text", "contentHash", "different-hash")), Map.class);
        client.indices().refresh(r -> r.index(CHUNKS));
        var r = Http.post(port, "/v1/audit/" + id + "/replay", ADMIN_KEY, "{}");
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.body()).contains("urn:rag:problem:content-hash-mismatch");
    }
}
