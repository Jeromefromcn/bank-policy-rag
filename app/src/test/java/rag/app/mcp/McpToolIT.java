package rag.app.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.search.Hit;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import rag.app.audit.AuditIndexAdmin;
import rag.app.audit.AuditRecord;
import rag.app.conversation.ConversationStore;
import rag.app.embed.Embedder;
import rag.app.embed.EmbeddingUnavailableException;
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

@TestPropertySource(properties = {"rag.env-prefix=t_mcp_tool", "rag.audit.index=t_mcp_tool-audit-v1",
        "rag.audit.reader-username=elastic", "rag.audit.reader-password=test-password"})
class McpToolIT extends IntegrationTest {

    static final String QUERY = "tolerance for disruption";
    static final String SECTIONS_INDEX = "t_mcp_tool-sections-struct400-v1";
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired ChunkIndexAdmin admin;
    @Autowired ChunkWriter writer;
    @Autowired SectionWriter sections;
    @Autowired StubChatModel stub;
    @Autowired ElasticsearchClient client;
    @Autowired AuditIndexAdmin auditAdmin;
    @MockitoSpyBean ConversationStore conversations;
    @MockitoSpyBean Embedder embedder;

    @BeforeEach
    void setUp() {
        stub.reset();
        TestIndex.rebuild(admin, writer, "t_mcp_tool", TestIndex.mixed(QUERY));
        if (!admin.exists(SECTIONS_INDEX)) {
            admin.createSections(SECTIONS_INDEX, Map.of("corpusVersion", "abcdef123456",
                    "chunkerVersion", "structural-v1-400-cl100k"));
            sections.bulkIndex(SECTIONS_INDEX, List.of(
                    new SectionDocument("sec-public", "spm-x", "Title spm-x", "1", "regulatory", List.of("1"),
                            List.of(), "public", "public section text", "abcdef123456"),
                    new SectionDocument("sec-restricted", "eb-secret", "Title eb-secret", "1", "internal", List.of("9"),
                            List.of(), "restricted", "restricted section text", "abcdef123456")));
            admin.pointAlias("t_mcp_tool-sections", SECTIONS_INDEX);
        }
    }

    @Test
    void searchDocsReturnsHitsWithSectionIds() {
        var r = McpHttp.callTool(port, KEY, "search_docs", Map.of("query", QUERY, "topK", 3));
        assertThat(McpHttp.isError(r)).isFalse();
        var body = McpHttp.text(r);
        assertThat(body.get("hits").size()).isEqualTo(3);
        var first = body.get("hits").get(0);
        assertThat(first.get("chunkId").asString()).isEqualTo("c-int");
        assertThat(first.get("sectionId").asString()).isEqualTo("s-c-int");
        assertThat(first.get("sectionPath").get(0).asString()).isEqualTo("2");
        assertThat(first.get("text").asString()).isEqualTo(QUERY);
    }

    @Test
    void searchDocsHonoursModeAndTopK() {
        var body = McpHttp.text(McpHttp.callTool(port, KEY, "search_docs",
                Map.of("query", QUERY, "mode", "BM25", "topK", 2)));
        assertThat(body.get("hits").size()).isEqualTo(2);
        assertThat(body.get("hits").get(0).get("text").asString()).isNotBlank();
    }

    @Test
    void rejectsInvalidArguments() {
        assertThat(McpHttp.failed(McpHttp.callTool(port, KEY, "search_docs", Map.of("query", " ")))).isTrue();
        assertThat(McpHttp.failed(McpHttp.callTool(port, KEY, "search_docs",
                Map.of("query", "x", "topK", 0)))).isTrue();
        assertThat(McpHttp.failed(McpHttp.callTool(port, KEY, "search_docs",
                Map.of("query", "x", "mode", "FUZZY")))).isTrue();
        assertThat(McpHttp.failed(McpHttp.callTool(port, KEY, "search_docs", Map.of()))).isTrue();
    }

    @Test
    void answerQuestionReturnsCitationsWithSectionIds() {
        var r = McpHttp.callTool(port, KEY, "answer_question", Map.of("question", QUERY));
        assertThat(McpHttp.isError(r)).isFalse();
        var body = McpHttp.text(r);
        assertThat(body.get("status").asString()).isEqualTo("ANSWERED");
        assertThat(body.get("answerId").asString()).startsWith("a_");
        assertThat(body.get("answer").asString()).isEqualTo("Answer [1].");
        var citation = body.get("citations").get(0);
        assertThat(citation.get("ref").asInt()).isEqualTo(1);
        assertThat(citation.get("chunkId").asString()).isEqualTo("c-int");
        assertThat(citation.get("sectionId").asString()).isEqualTo("s-c-int");
    }

    @Test
    void answerQuestionDoesNotTouchAConversation() {
        McpHttp.callTool(port, KEY, "answer_question", Map.of("question", QUERY));
        verify(conversations, never()).create(any());
        verify(conversations, never()).history(any(), any());
        verify(conversations, never()).append(any(), any(), any());
    }

    @Test
    void answerQuestionRefusalMatchesRest() {
        TestIndex.rebuild(admin, writer, "t_mcp_tool", TestIndex.hiddenOnly(QUERY));
        var rest = JSON.readTree(Http.post(port, "/v1/ask", PUBLIC_KEY,
                JSON.writeValueAsString(Map.of("question", QUERY))).body());
        var mcp = McpHttp.text(McpHttp.callTool(port, PUBLIC_KEY, "answer_question",
                Map.of("question", QUERY)));
        assertThat(mcp.get("status").asString()).isEqualTo(rest.get("status").asString());
        assertThat(mcp.get("refusalReason").asString()).isEqualTo(rest.get("refusalReason").asString());
        assertThat(stub.calls()).isZero();
    }

    @Test
    void getSectionReturnsTheFullText() {
        var r = McpHttp.callTool(port, KEY, "get_section", Map.of("sectionId", "sec-public"));
        assertThat(McpHttp.isError(r)).isFalse();
        var body = McpHttp.text(r);
        assertThat(body.get("sectionId").asString()).isEqualTo("sec-public");
        assertThat(body.get("content").asString()).isEqualTo("public section text");
        assertThat(body.get("classification").asString()).isEqualTo("public");
    }

    @Test
    void hiddenAndUnknownSectionsAreIdenticalForAPublicCaller() {
        var hidden = McpHttp.callTool(port, PUBLIC_KEY, "get_section", Map.of("sectionId", "sec-restricted"));
        var unknown = McpHttp.callTool(port, PUBLIC_KEY, "get_section", Map.of("sectionId", "no-such-section"));
        assertThat(McpHttp.isError(hidden)).isTrue();
        assertThat(McpHttp.isError(unknown)).isTrue();
        assertThat(McpHttp.message(hidden)).isEqualTo(McpHttp.message(unknown));
    }

    @Test
    void auditFailureReturnsErrorAndNoContent() throws Exception {
        client.indices().close(c -> c.index("t_mcp_tool-audit-v1"));
        try {
            var r = McpHttp.callTool(port, KEY, "search_docs", Map.of("query", QUERY));
            assertThat(McpHttp.isError(r)).isTrue();
            assertThat(McpHttp.message(r)).contains("audit record could not be written");
        } finally {
            client.indices().open(o -> o.index("t_mcp_tool-audit-v1"));
        }
    }

    @Test
    void backendFailureIsGeneric() {
        doThrow(new EmbeddingUnavailableException("secret detail", null))
                .when(embedder).embedQuery(anyString());
        var r = McpHttp.callTool(port, KEY, "search_docs", Map.of("query", QUERY, "mode", "VECTOR"));
        assertThat(McpHttp.isError(r)).isTrue();
        assertThat(McpHttp.message(r)).isEqualTo("The service could not complete the request.")
                .doesNotContain("secret detail");
    }

    @Test
    void backendFailureWritesNoAuditRecord() throws Exception {
        try {
            client.indices().delete(d -> d.index("t_mcp_tool-audit-v1"));
        } catch (IOException | co.elastic.clients.elasticsearch._types.ElasticsearchException ignored) {
            // first run, or already gone
        }
        auditAdmin.ensure();
        doThrow(new EmbeddingUnavailableException("secret detail", null))
                .when(embedder).embedQuery(anyString());
        var r = McpHttp.callTool(port, KEY, "search_docs", Map.of("query", QUERY, "mode", "VECTOR"));
        assertThat(McpHttp.isError(r)).isTrue();
        client.indices().refresh(x -> x.index("t_mcp_tool-audit-v1"));
        var records = client.search(s -> s.index("t_mcp_tool-audit-v1")
                        .query(q -> q.term(t -> t.field("endpoint").value("mcp:search_docs"))), AuditRecord.class)
                .hits().hits().stream().map(Hit::source).toList();
        assertThat(records).isEmpty();
    }
}
