package rag.app.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.common.McpTransportContext;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import rag.app.security.Principal;
import rag.core.model.Clearance;

class McpToolContextTest {

    @Test
    void requiresPrincipal() {
        assertThatThrownBy(() -> McpToolContext.require(McpTransportContext.EMPTY))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void returnsThePrincipal() {
        var principal = new Principal("alice", Clearance.RESTRICTED, Set.of());
        var context = McpTransportContext.create(Map.of(McpToolContext.PRINCIPAL, principal));
        assertThat(McpToolContext.require(context)).isSameAs(principal);
    }
}
