package rag.app.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import rag.app.es.ChunkIndexAdmin;
import rag.app.es.ChunkWriter;
import rag.app.support.IntegrationTest;
import rag.app.support.McpHttp;
import rag.app.support.TestIndex;

/** One {@code rag.mcp.tool} span per call, with the tool name and the result status (spec 06 §4.4). */
@TestPropertySource(properties = "rag.env-prefix=t_mcp_obs")
@Import(McpObservationIT.Config.class)
class McpObservationIT extends IntegrationTest {

    static final String QUERY = "tolerance for disruption";

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        @Primary
        TestObservationRegistry testObservationRegistry() {
            return TestObservationRegistry.create();
        }
    }

    @Autowired TestObservationRegistry observations;
    @Autowired MeterRegistry meters;
    @Autowired ChunkIndexAdmin admin;
    @Autowired ChunkWriter writer;

    @BeforeEach
    void setUp() {
        observations.clear();
        TestIndex.rebuild(admin, writer, "t_mcp_obs", TestIndex.mixed(QUERY));
    }

    @Test
    void searchToolCreatesSpanAndMetrics() {
        McpHttp.callTool(port, KEY, "search_docs", Map.of("query", QUERY));
        TestObservationRegistryAssert.assertThat(observations)
                .hasObservationWithNameEqualTo("rag.mcp.tool").that()
                .hasLowCardinalityKeyValue("tool", "search_docs")
                .hasLowCardinalityKeyValue("status", "ok");
        assertThat(meters.get("rag.requests").tag("endpoint", "mcp:search_docs")
                .tag("status", "ok").counter().count()).isEqualTo(1);
    }

    @Test
    void refusedAnswerTagsTheSpanAsRefused() {
        TestIndex.rebuild(admin, writer, "t_mcp_obs", TestIndex.hiddenOnly(QUERY));
        McpHttp.callTool(port, PUBLIC_KEY, "answer_question", Map.of("question", QUERY));
        TestObservationRegistryAssert.assertThat(observations)
                .hasObservationWithNameEqualTo("rag.mcp.tool").that()
                .hasLowCardinalityKeyValue("tool", "answer_question")
                .hasLowCardinalityKeyValue("status", "refused");
    }
}
