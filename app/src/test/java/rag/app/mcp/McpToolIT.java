package rag.app.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import rag.app.conversation.ConversationStore;
import rag.app.es.ChunkIndexAdmin;
import rag.app.es.ChunkWriter;
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
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired ChunkIndexAdmin admin;
    @Autowired ChunkWriter writer;
    @Autowired StubChatModel stub;
    @MockitoSpyBean ConversationStore conversations;

    @BeforeEach
    void setUp() {
        stub.reset();
        TestIndex.rebuild(admin, writer, "t_mcp_tool", TestIndex.mixed(QUERY));
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
    void answerQuestionDoesNotCreateAConversation() {
        McpHttp.callTool(port, KEY, "answer_question", Map.of("question", QUERY));
        verify(conversations, never()).create(any());
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
}
