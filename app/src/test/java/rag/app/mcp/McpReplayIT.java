package rag.app.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import rag.app.audit.AuditIndexAdmin;
import rag.app.audit.AuditRecord;
import rag.app.es.ChunkIndexAdmin;
import rag.app.es.ChunkWriter;
import rag.app.es.SectionDocument;
import rag.app.es.SectionWriter;
import rag.app.support.Http;
import rag.app.support.IntegrationTest;
import rag.app.support.McpHttp;
import rag.app.support.StubChatModel;
import rag.app.support.TestIndex;
import tools.jackson.databind.json.JsonMapper;

/** MCP answer records join {@code ask} records as replayable; search and section records stay 409 (spec 06 §4.5). */
@TestPropertySource(properties = {"rag.env-prefix=t_mcp_replay", "rag.audit.index=t_mcp_replay-audit-v1",
        "rag.audit.reader-username=elastic", "rag.audit.reader-password=test-password"})
class McpReplayIT extends IntegrationTest {

    static final String QUERY = "tolerance for disruption";
    static final String AUDIT_INDEX = "t_mcp_replay-audit-v1";
    static final String SECTIONS_INDEX = "t_mcp_replay-sections-struct400-v1";
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired ChunkIndexAdmin admin;
    @Autowired ChunkWriter writer;
    @Autowired SectionWriter sections;
    @Autowired StubChatModel stub;
    @Autowired AuditIndexAdmin auditAdmin;
    @Autowired ElasticsearchClient client;

    @BeforeEach
    void setUp() {
        stub.reset();
        TestIndex.rebuild(admin, writer, "t_mcp_replay", TestIndex.mixed(QUERY));
        if (!admin.exists(SECTIONS_INDEX)) {
            admin.createSections(SECTIONS_INDEX, Map.of("corpusVersion", "abcdef123456",
                    "chunkerVersion", "structural-v1-400-cl100k"));
            sections.bulkIndex(SECTIONS_INDEX, List.of(
                    new SectionDocument("sec-public", "spm-x", "Title spm-x", "1", "regulatory", List.of("1"),
                            List.of(), "public", "public section text", "abcdef123456")));
            admin.pointAlias("t_mcp_replay-sections", SECTIONS_INDEX);
        }
        try {
            client.indices().delete(d -> d.index(AUDIT_INDEX));
        } catch (IOException | co.elastic.clients.elasticsearch._types.ElasticsearchException ignored) {
            // first run, or already gone
        }
        auditAdmin.ensure();
    }

    @Test
    void mcpAnswerReplaysTheRecordedPrompt() {
        var asked = McpHttp.text(McpHttp.callTool(port, KEY, "answer_question", Map.of("question", QUERY)));
        stub.replies("Replayed [1].");
        var r = Http.post(port, "/v1/audit/" + asked.get("answerId").asString() + "/replay", ADMIN_KEY, "{}");
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        var body = JSON.readTree(r.body());
        assertThat(body.get("modelCall").asBoolean()).isTrue();
        assertThat(body.get("replay").get("answer").asString()).isEqualTo("Replayed [1].");
    }

    @Test
    void mcpRefusalReplaysWithoutAModelCall() {
        TestIndex.rebuild(admin, writer, "t_mcp_replay", TestIndex.hiddenOnly(QUERY));
        var asked = McpHttp.text(McpHttp.callTool(port, PUBLIC_KEY, "answer_question", Map.of("question", QUERY)));
        int before = stub.calls();
        var r = Http.post(port, "/v1/audit/" + asked.get("answerId").asString() + "/replay", ADMIN_KEY, "{}");
        assertThat(r.status()).isEqualTo(200);
        assertThat(JSON.readTree(r.body()).get("modelCall").asBoolean()).isFalse();
        assertThat(stub.calls()).isEqualTo(before);
    }

    @Test
    void mcpAnswerHashMismatchIs409() throws Exception {
        var asked = McpHttp.text(McpHttp.callTool(port, KEY, "answer_question", Map.of("question", QUERY)));
        client.delete(d -> d.index("t_mcp_replay-chunks").id("c-int"));
        client.indices().refresh(r -> r.index("t_mcp_replay-chunks"));
        var r = Http.post(port, "/v1/audit/" + asked.get("answerId").asString() + "/replay", ADMIN_KEY, "{}");
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.body()).contains("urn:rag:problem:content-hash-mismatch");
    }

    @Test
    void searchAndSectionRecordsAre409() throws Exception {
        McpHttp.callTool(port, KEY, "search_docs", Map.of("query", QUERY));
        McpHttp.callTool(port, KEY, "get_section", Map.of("sectionId", "sec-public"));
        for (String endpoint : List.of("mcp:search_docs", "mcp:get_section")) {
            String id = auditRecord(endpoint).id();
            var r = Http.post(port, "/v1/audit/" + id + "/replay", ADMIN_KEY, "{}");
            assertThat(r.status()).isEqualTo(409);
            assertThat(r.body()).contains("urn:rag:problem:replay-unsupported");
        }
    }

    AuditRecord auditRecord(String endpoint) throws IOException {
        client.indices().refresh(r -> r.index(AUDIT_INDEX));
        return client.search(s -> s.index(AUDIT_INDEX)
                .query(q -> q.term(t -> t.field("endpoint").value(endpoint))), AuditRecord.class)
                .hits().hits().getFirst().source();
    }
}
