package rag.app.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.search.Hit;
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

/** Each completed MCP call writes exactly one audit record with the right endpoint, identity and status. */
@TestPropertySource(properties = {"rag.env-prefix=t_mcp_audit", "rag.audit.index=t_mcp_audit-audit-v1",
        "rag.audit.reader-username=elastic", "rag.audit.reader-password=test-password"})
class McpAuditIT extends IntegrationTest {

    static final String QUERY = "tolerance for disruption";
    static final String AUDIT_INDEX = "t_mcp_audit-audit-v1";
    static final String SECTIONS_INDEX = "t_mcp_audit-sections-struct400-v1";

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired ChunkIndexAdmin admin;
    @Autowired ChunkWriter writer;
    @Autowired SectionWriter sections;
    @Autowired StubChatModel stub;
    @Autowired AuditIndexAdmin auditAdmin;
    @Autowired ElasticsearchClient client;

    @BeforeEach
    void resetAuditAndIndex() {
        stub.reset();
        TestIndex.rebuild(admin, writer, "t_mcp_audit", TestIndex.mixed(QUERY));
        if (!admin.exists(SECTIONS_INDEX)) {
            admin.createSections(SECTIONS_INDEX, Map.of("corpusVersion", "abcdef123456",
                    "chunkerVersion", "structural-v1-400-cl100k"));
            sections.bulkIndex(SECTIONS_INDEX, List.of(
                    new SectionDocument("sec-public", "spm-x", "Title spm-x", "1", "regulatory", List.of("1"),
                            List.of(), "public", "public section text", "abcdef123456")));
            admin.pointAlias("t_mcp_audit-sections", SECTIONS_INDEX);
        }
        try {
            client.indices().delete(d -> d.index(AUDIT_INDEX));
        } catch (IOException | co.elastic.clients.elasticsearch._types.ElasticsearchException ignored) {
            // first run, or already gone
        }
        auditAdmin.ensure();
    }

    @Test
    void searchWritesOneRecord() throws Exception {
        McpHttp.callTool(port, KEY, "search_docs", Map.of("query", QUERY));
        var records = records("mcp:search_docs");
        assertThat(records).hasSize(1);
        var rec = records.getFirst();
        assertThat(rec.queryId()).startsWith("q_");
        assertThat(rec.principal()).isEqualTo("tester");
        assertThat(rec.clearance()).isEqualTo("restricted");
        assertThat(rec.status()).isEqualTo("OK");
        assertThat(rec.retrieval().hits()).isNotEmpty();
    }

    @Test
    void answerRecordIsReadableByTheAdminEndpoint() {
        var body = McpHttp.text(McpHttp.callTool(port, KEY, "answer_question", Map.of("question", QUERY)));
        var r = Http.get(port, "/v1/audit/" + body.get("answerId").asString(), ADMIN_KEY);
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        var rec = JSON.readTree(r.body());
        assertThat(rec.get("endpoint").asString()).isEqualTo("mcp:answer_question");
        assertThat(rec.get("renderedPrompt").asString()).contains(QUERY);
        assertThat(rec.get("status").asString()).isEqualTo("ANSWERED");
    }

    @Test
    void getSectionWritesOkAndNotFound() throws Exception {
        McpHttp.callTool(port, KEY, "get_section", Map.of("sectionId", "sec-public"));
        McpHttp.callTool(port, KEY, "get_section", Map.of("sectionId", "no-such-section"));
        var records = records("mcp:get_section");
        assertThat(records).extracting(AuditRecord::status).containsExactlyInAnyOrder("OK", "NOT_FOUND");
        assertThat(records).allSatisfy(rec -> {
            assertThat(rec.queryId()).startsWith("s_");
            assertThat(rec.question()).isIn("sec-public", "no-such-section");
        });
    }

    @Test
    void invalidInputWritesNoRecord() throws Exception {
        McpHttp.callTool(port, KEY, "search_docs", Map.of("query", " "));
        assertThat(records("mcp:search_docs")).isEmpty();
    }

    List<AuditRecord> records(String endpoint) throws IOException {
        client.indices().refresh(r -> r.index(AUDIT_INDEX));
        return client.search(s -> s.index(AUDIT_INDEX)
                .query(q -> q.term(t -> t.field("endpoint").value(endpoint))), AuditRecord.class)
                .hits().hits().stream().map(Hit::source).toList();
    }
}
