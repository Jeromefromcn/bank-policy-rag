package rag.app.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import rag.app.es.ChunkIndexAdmin;
import rag.app.es.ChunkWriter;
import rag.app.support.IntegrationTest;
import rag.app.support.McpHttp;
import rag.app.support.StubChatModel;
import rag.app.support.TestIndex;

@TestPropertySource(properties = {"rag.env-prefix=t_mcp_tool", "rag.audit.index=t_mcp_tool-audit-v1",
        "rag.audit.reader-username=elastic", "rag.audit.reader-password=test-password"})
class McpToolIT extends IntegrationTest {

    static final String QUERY = "tolerance for disruption";

    @Autowired ChunkIndexAdmin admin;
    @Autowired ChunkWriter writer;
    @Autowired StubChatModel stub;

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
}
