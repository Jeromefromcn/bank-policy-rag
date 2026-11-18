package rag.app.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import rag.app.security.Principal;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stateless Streamable HTTP transport (spec 06 §3.1-§3.2). The context extractor runs on the servlet request
 * thread inside the security filter chain and snapshots the authenticated principal, so a tool never reads
 * {@code SecurityContextHolder} on the SDK's thread and never runs without an identity.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class McpServerConfig {

    @Bean
    @ConditionalOnMissingBean
    WebMvcStatelessServerTransport webMvcStatelessServerTransport(
            @Qualifier("mcpServerJsonMapper") JsonMapper json,
            McpServerStreamableHttpProperties properties) {
        return WebMvcStatelessServerTransport.builder()
                .jsonMapper(new JacksonMcpJsonMapper(json))
                .messageEndpoint(properties.getMcpEndpoint())
                .contextExtractor(request -> {
                    var authentication = SecurityContextHolder.getContext().getAuthentication();
                    if (authentication != null && authentication.getPrincipal() instanceof Principal principal) {
                        return McpTransportContext.create(java.util.Map.of(McpToolContext.PRINCIPAL, principal));
                    }
                    return McpTransportContext.EMPTY;
                })
                .build();
    }
}
