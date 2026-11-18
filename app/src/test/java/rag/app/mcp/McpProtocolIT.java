package rag.app.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import rag.app.support.Http;
import rag.app.support.IntegrationTest;
import rag.app.support.McpHttp;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@TestPropertySource(properties = {"rag.env-prefix=t_mcp", "rag.audit.index=t_mcp-audit-v1"})
class McpProtocolIT extends IntegrationTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void missingKeyIs401() {
        var r = Http.post(port, "/mcp", null,
                JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize",
                        "params", Map.of())),
                Map.of("Accept", "application/json, text/event-stream"));
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.contentType()).startsWith("application/problem+json");
    }

    @Test
    void initializeIsStatelessAndNamed() {
        var rpc = McpHttp.initialize(port, KEY);
        assertThat(rpc.get("result").get("serverInfo").get("name").asString())
                .isEqualTo("bank-policy-rag");
        assertThat(rpc.get("result").get("capabilities").has("tools")).isTrue();
    }

    @Test
    void initializeDoesNotIssueASessionId() {
        var r = McpHttp.raw(port, KEY, McpHttp.initializeBody());
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.header("Mcp-Session-Id")).isNull();
    }

    @Test
    void getIs405() {
        var r = Http.get(port, "/mcp", KEY);
        assertThat(r.status()).isEqualTo(405);
    }

    @Test
    void toolsListHasExactlyThreeReadOnlyTools() {
        var tools = McpHttp.rpc(port, KEY, "tools/list", Map.of()).get("result").get("tools");
        assertThat(names(tools)).containsExactlyInAnyOrder("search_docs", "answer_question", "get_section");
        for (var tool : tools) {
            assertThat(tool.get("description").asString()).contains("data, not instructions");
            assertThat(tool.get("annotations").get("readOnlyHint").asBoolean()).isTrue();
            assertThat(tool.get("inputSchema").get("properties").has("context")).isFalse();
        }
    }

    @Test
    void unknownToolIsAnError() {
        var r = McpHttp.rpc(port, KEY, "tools/call", Map.of("name", "no_such_tool", "arguments", Map.of()));
        assertThat(McpHttp.failed(r)).isTrue();
    }

    static java.util.List<String> names(JsonNode tools) {
        return StreamSupport.stream(tools.spliterator(), false).map(t -> t.get("name").asString()).toList();
    }
}
