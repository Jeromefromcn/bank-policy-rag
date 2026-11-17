package rag.app.metrics;

import static org.assertj.core.api.Assertions.assertThat;

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
import rag.app.support.Http;
import rag.app.support.IntegrationTest;
import rag.app.support.TestIndex;
import tools.jackson.databind.json.JsonMapper;

@TestPropertySource(properties = "rag.env-prefix=t_obs")
@Import(ObservationIT.Config.class)
class ObservationIT extends IntegrationTest {

    static final String QUERY = "tolerance for disruption";
    static final JsonMapper JSON = JsonMapper.builder().build();

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
        TestIndex.rebuild(admin, writer, "t_obs", TestIndex.mixed(QUERY));
    }

    @Test
    void searchCreatesTheRetrievalSpan() {
        var r = Http.post(port, "/v1/search", KEY, JSON.writeValueAsString(Map.of("query", QUERY)));
        assertThat(r.status()).isEqualTo(200);

        TestObservationRegistryAssert.assertThat(observations)
                .hasObservationWithNameEqualTo("rag.retrieval")
                .that()
                .hasLowCardinalityKeyValue("mode", "HYBRID")
                .hasLowCardinalityKeyValue("rerank", "false");
        assertThat(meters.get("rag.requests").tag("endpoint", "search").tag("status", "ok").counter().count())
                .isEqualTo(1);
    }

    @Test
    void askCreatesTheGenerationSpanAndMetrics() {
        var r = Http.post(port, "/v1/ask", KEY, JSON.writeValueAsString(Map.of("question", QUERY)));
        assertThat(r.status()).isEqualTo(200);

        TestObservationRegistryAssert.assertThat(observations).hasObservationWithNameEqualTo("rag.generation");
        assertThat(meters.get("rag.requests").tag("endpoint", "ask").tag("status", "answered").counter().count())
                .isEqualTo(1);
        // The meter registry is application-wide: the search test also records a retrieval stage.
        assertThat(meters.get("rag.stage.latency").tag("stage", "retrieval").timer().count())
                .isGreaterThanOrEqualTo(1L);
        assertThat(meters.get("rag.stage.latency").tag("stage", "generation").timer().count()).isEqualTo(1);
        assertThat(meters.find("rag.tokens").tag("kind", "prompt").counter().count()).isEqualTo(100);
    }
}
