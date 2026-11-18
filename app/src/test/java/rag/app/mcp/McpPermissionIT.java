package rag.app.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import rag.app.es.ChunkIndexAdmin;
import rag.app.es.ChunkWriter;
import rag.app.support.Http;
import rag.app.support.IntegrationTest;
import rag.app.support.McpHttp;
import rag.app.support.StubChatModel;
import rag.app.support.TestIndex;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * MCP and REST must produce the same permission outcome for the same key: the clearance pre-filter, the refusal
 * reasons and the hidden-vs-absent bodies (spec 06 §5, §7.3).
 */
@TestPropertySource(properties = {"rag.env-prefix=t_mcp_perm", "rag.audit.index=t_mcp_perm-audit-v1",
        "rag.audit.reader-username=elastic", "rag.audit.reader-password=test-password"})
class McpPermissionIT extends IntegrationTest {

    static final String QUERY = "tolerance for disruption";
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired ChunkIndexAdmin admin;
    @Autowired ChunkWriter writer;
    @Autowired StubChatModel stub;

    @BeforeEach
    void setUp() {
        stub.reset();
    }

    @Test
    void sameQuestionDifferentKeys() {
        TestIndex.rebuild(admin, writer, "t_mcp_perm", TestIndex.hiddenOnly(QUERY));
        for (String key : List.of(KEY, INTERNAL_KEY, PUBLIC_KEY)) {
            var rest = JSON.readTree(Http.post(port, "/v1/ask", key,
                    JSON.writeValueAsString(Map.of("question", QUERY))).body());
            var mcp = McpHttp.text(McpHttp.callTool(port, key, "answer_question", Map.of("question", QUERY)));
            assertThat(mcp.get("status").asString()).isEqualTo(rest.get("status").asString());
            if (rest.path("refusalReason").isString()) {
                assertThat(mcp.get("refusalReason").asString()).isEqualTo(rest.get("refusalReason").asString());
            } else {
                // REST serialises an answered response with refusalReason null; MCP does the same.
                assertThat(mcp.path("refusalReason").isNull()).isTrue();
            }
        }
    }

    @Test
    void searchParityForEveryClearance() {
        TestIndex.rebuild(admin, writer, "t_mcp_perm", TestIndex.threeLevels(QUERY));
        for (String key : List.of(KEY, INTERNAL_KEY, PUBLIC_KEY)) {
            var rest = JSON.readTree(Http.post(port, "/v1/search", key,
                    JSON.writeValueAsString(Map.of("query", QUERY, "topK", 10))).body());
            var mcp = McpHttp.text(McpHttp.callTool(port, key, "search_docs",
                    Map.of("query", QUERY, "topK", 10)));
            assertThat(docIds(mcp.get("hits"))).isEqualTo(docIds(rest.get("hits")));
        }
    }

    @Test
    void hiddenAndAbsentMatch() {
        TestIndex.rebuild(admin, writer, "t_mcp_perm", TestIndex.hiddenOnly(QUERY));
        var hidden = McpHttp.text(McpHttp.callTool(port, PUBLIC_KEY, "answer_question",
                Map.of("question", QUERY)));
        var absent = McpHttp.text(McpHttp.callTool(port, PUBLIC_KEY, "answer_question",
                Map.of("question", "zebra quantum harvest")));
        assertThat(hidden.get("status")).isEqualTo(absent.get("status"));
        assertThat(hidden.has("refusalReason")).isEqualTo(absent.has("refusalReason"));
        assertThat(hidden.get("citations").size()).isZero();
        assertThat(absent.get("citations").size()).isZero();
    }

    static List<String> docIds(JsonNode hits) {
        return StreamSupport.stream(hits.spliterator(), false)
                .map(h -> h.get("docId").asString()).toList();
    }
}
