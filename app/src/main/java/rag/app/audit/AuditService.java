package rag.app.audit;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import rag.app.metrics.Observations;
import rag.app.metrics.RequestMetrics;
import rag.app.metrics.Tracing;

/** Writes audit records and supplies the trace id; the record builders live in the request services. */
@Component
public class AuditService {

    private final AuditStore store;
    private final ObjectProvider<Tracer> tracer;
    private final RequestMetrics metrics;
    private final ObservationRegistry observations;

    public AuditService(AuditStore store, ObjectProvider<Tracer> tracer, RequestMetrics metrics,
            ObservationRegistry observations) {
        this.store = store;
        this.tracer = tracer;
        this.metrics = metrics;
        this.observations = observations;
    }

    public String traceId() {
        Tracer t = tracer.getIfAvailable();
        return t == null ? null : Tracing.traceId(t);
    }

    /** Writes one record and returns the write time; a failure is a 503 and increments the failure counter. */
    public long write(AuditRecord record) {
        long t0 = System.nanoTime();
        try {
            Observations.observe(observations, "rag.audit.write", Map.of("endpoint", record.endpoint()),
                    () -> {
                        store.write(record);
                        return null;
                    });
            return (System.nanoTime() - t0) / 1_000_000;
        } catch (RuntimeException e) {
            metrics.auditWriteFailure(record.endpoint());
            throw e;
        }
    }
}
