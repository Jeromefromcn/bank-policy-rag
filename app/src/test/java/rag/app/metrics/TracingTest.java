package rag.app.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.test.simple.SimpleTracer;
import org.junit.jupiter.api.Test;

class TracingTest {

    @Test
    void noCurrentSpanMeansNoTraceId() {
        assertThat(Tracing.traceId(new SimpleTracer())).isNull();
    }

    @Test
    void currentSpanExposesItsTraceId() {
        var tracer = new SimpleTracer();
        var span = tracer.nextSpan().name("t").start();
        try (var scope = tracer.withSpan(span)) {
            assertThat(Tracing.traceId(tracer)).isEqualTo(span.context().traceId());
        } finally {
            span.end();
        }
    }
}
