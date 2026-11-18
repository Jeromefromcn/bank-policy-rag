package rag.app.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** JSON-RPC over the stateless Streamable HTTP endpoint, with the {@code Accept} header the MCP SDK requires. */
public final class McpHttp {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AtomicInteger ID = new AtomicInteger();
    static final String ACCEPT = "application/json, text/event-stream";

    private McpHttp() {}

    public static Map<String, Object> initializeBody() {
        return Map.of("jsonrpc", "2.0", "id", ID.incrementAndGet(), "method", "initialize",
                "params", Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "test", "version", "1")));
    }

    public static Http.Response raw(int port, String key, Map<String, Object> body) {
        return Http.post(port, "/mcp", key, JSON.writeValueAsString(body), Map.of("Accept", ACCEPT));
    }

    public static JsonNode rpc(int port, String key, String method, Map<String, Object> params) {
        var r = raw(port, key, Map.of("jsonrpc", "2.0", "id", ID.incrementAndGet(), "method", method,
                "params", params));
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    public static JsonNode initialize(int port, String key) {
        return rpc(port, key, "initialize", Map.of("protocolVersion", "2025-06-18",
                "capabilities", Map.of(), "clientInfo", Map.of("name", "test", "version", "1")));
    }

    public static JsonNode callTool(int port, String key, String tool, Map<String, Object> args) {
        return rpc(port, key, "tools/call", Map.of("name", tool, "arguments", args));
    }

    public static boolean isError(JsonNode rpc) {
        return rpc.path("result").path("isError").asBoolean(false);
    }

    /** True for a JSON-RPC {@code error} or a tool result with {@code isError}. */
    public static boolean failed(JsonNode rpc) {
        return rpc.has("error") || isError(rpc);
    }

    /** The raw {@code result.content[0].text}. */
    public static String message(JsonNode rpc) {
        return rpc.path("result").path("content").path(0).path("text").asString();
    }

    /** The tool result text parsed as JSON. */
    public static JsonNode text(JsonNode rpc) {
        return JSON.readTree(message(rpc));
    }
}
