package rag.app.metrics;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

/** The current trace id, for the audit record; null when tracing is off. */
public final class Tracing {

    private Tracing() {}

    public static String traceId(Tracer tracer) {
        Span span = tracer.currentSpan();
        return span == null ? null : span.context().traceId();
    }
}
