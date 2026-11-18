package rag.app.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import java.util.List;
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
import rag.app.es.SectionDocument;
import rag.app.es.SectionWriter;
import rag.app.support.IntegrationTest;
import rag.app.support.McpHttp;
import rag.app.support.TestIndex;

/** One {@code rag.mcp.tool} span per call, with the tool name and the result status (spec 06 §4.4). */
@TestPropertySource(properties = "rag.env-prefix=t_mcp_obs")
@Import(McpObservationIT.Config.class)
class McpObservationIT extends IntegrationTest {

    static final String QUERY = "tolerance for disruption";
    static final String SECTIONS_INDEX = "t_mcp_obs-sections-struct400-v1";
    static final String AUDIT_INDEX = "audit-t_mcp_obs-v1";

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
    @Autowired SectionWriter sections;
    @Autowired ElasticsearchClient client;

    @BeforeEach
    void setUp() {
        observations.clear();
        TestIndex.rebuild(admin, writer, "t_mcp_obs", TestIndex.mixed(QUERY));
        if (!admin.exists(SECTIONS_INDEX)) {
            admin.createSections(SECTIONS_INDEX, Map.of("corpusVersion", "abcdef123456",
                    "chunkerVersion", "structural-v1-400-cl100k"));
            sections.bulkIndex(SECTIONS_INDEX, List.of(
                    new SectionDocument("sec-public", "spm-x", "Title spm-x", "1", "regulatory", List.of("1"),
                            List.of(), "public", "public section text", "abcdef123456")));
            admin.pointAlias("t_mcp_obs-sections", SECTIONS_INDEX);
        }
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

    @Test
    void emptyRetrievalOnTheAnswerToolUsesItsEndpointLabel() {
        TestIndex.rebuild(admin, writer, "t_mcp_obs", TestIndex.hiddenOnly(QUERY));
        McpHttp.callTool(port, PUBLIC_KEY, "answer_question", Map.of("question", QUERY));
        assertThat(meters.get("rag.retrieval.empty").tag("endpoint", "mcp:answer_question")
                .tag("cause", "no_accessible_sources").counter().count()).isGreaterThanOrEqualTo(1);
        assertThat(meters.find("rag.retrieval.empty").tag("endpoint", "ask").counter()).isNull();
    }

    @Test
    void getSectionRecordsOkStatus() {
        McpHttp.callTool(port, KEY, "get_section", Map.of("sectionId", "sec-public"));
        assertThat(meters.get("rag.requests").tag("endpoint", "mcp:get_section")
                .tag("status", "ok").counter().count()).isEqualTo(1);
        TestObservationRegistryAssert.assertThat(observations)
                .hasObservationWithNameEqualTo("rag.mcp.tool").that()
                .hasLowCardinalityKeyValue("tool", "get_section")
                .hasLowCardinalityKeyValue("status", "ok");
    }

    @Test
    void getSectionRecordsNotFoundStatus() {
        McpHttp.callTool(port, KEY, "get_section", Map.of("sectionId", "no-such-section"));
        assertThat(meters.get("rag.requests").tag("endpoint", "mcp:get_section")
                .tag("status", "not_found").counter().count()).isEqualTo(1);
        TestObservationRegistryAssert.assertThat(observations)
                .hasObservationWithNameEqualTo("rag.mcp.tool").that()
                .hasLowCardinalityKeyValue("tool", "get_section")
                .hasLowCardinalityKeyValue("status", "not_found");
    }

    @Test
    void argumentErrorCreatesAnErrorSpan() {
        McpHttp.callTool(port, KEY, "search_docs", Map.of("query", " "));
        TestObservationRegistryAssert.assertThat(observations)
                .hasObservationWithNameEqualTo("rag.mcp.tool").that()
                .hasLowCardinalityKeyValue("tool", "search_docs")
                .hasLowCardinalityKeyValue("status", "error");
    }

    @Test
    void auditFailureCreatesAnErrorSpan() throws Exception {
        client.indices().close(c -> c.index(AUDIT_INDEX));
        try {
            McpHttp.callTool(port, KEY, "search_docs", Map.of("query", QUERY));
            TestObservationRegistryAssert.assertThat(observations)
                    .hasObservationWithNameEqualTo("rag.mcp.tool").that()
                    .hasLowCardinalityKeyValue("tool", "search_docs")
                    .hasLowCardinalityKeyValue("status", "error");
        } finally {
            client.indices().open(o -> o.index(AUDIT_INDEX));
        }
    }
}
