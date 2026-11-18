package rag.app.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import rag.app.support.Http;
import rag.app.support.IntegrationTest;
import rag.app.support.McpHttp;
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
}
