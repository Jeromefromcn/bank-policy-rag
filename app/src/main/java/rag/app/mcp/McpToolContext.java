package rag.app.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import rag.app.security.Principal;

/**
 * Reads the caller identity out of the per-request MCP transport context (spec 06 §3.2). Tool methods must not
 * read {@code SecurityContextHolder}: the MCP SDK may invoke them on a different thread. A missing principal is
 * an error, never an anonymous or lower-clearance query.
 */
public final class McpToolContext {

    public static final String PRINCIPAL = "principal";

    private McpToolContext() {}

    public static Principal require(McpTransportContext context) {
        Object value = context == null ? null : context.get(PRINCIPAL);
        if (value instanceof Principal principal) {
            return principal;
        }
        throw new IllegalStateException("no authenticated principal in the MCP request");
    }
}
