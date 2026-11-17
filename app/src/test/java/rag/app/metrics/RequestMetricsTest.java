package rag.app.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import rag.app.audit.InternalRefusalCause;

class RequestMetricsTest {

    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final RequestMetrics metrics = new RequestMetrics(registry);

    @Test
    void countersCarryOnlyBoundedTags() {
        metrics.request("ask", "refused");
        metrics.refusal("LOW_RETRIEVAL_SCORE");
        metrics.emptyRetrieval("ask", InternalRefusalCause.NO_ACCESSIBLE_SOURCES);
        metrics.tokens("deepseek-flash", 100, 10);

        assertThat(registry.get("rag.requests").tag("endpoint", "ask").tag("status", "refused").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("rag.refusals").tag("reason", "LOW_RETRIEVAL_SCORE").counter().count()).isEqualTo(1);
        assertThat(registry.get("rag.retrieval.empty").tag("cause", "no_accessible_sources").counter().count())
                .isEqualTo(1);
        assertThat(registry.find("rag.tokens").tag("kind", "prompt").tag("model", "deepseek-flash").counter().count())
                .isEqualTo(100);
        assertThat(registry.find("rag.tokens").tag("kind", "completion").tag("model", "deepseek-flash").counter().count())
                .isEqualTo(10);
    }

    @Test
    void stageTimerIsReused() {
        metrics.stage("retrieval", 5);
        metrics.stage("retrieval", 7);

        assertThat(registry.get("rag.stage.latency").tag("stage", "retrieval").timer().count()).isEqualTo(2);
    }

    @Test
    void nullTokensAreSkipped() {
        metrics.tokens("deepseek-flash", null, null);

        assertThat(registry.find("rag.tokens").counters()).isEmpty();
    }
}
