package rag.app.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import rag.app.audit.InternalRefusalCause;

/**
 * Bounded-tag metrics (spec 05 §5.2). Cache the meter per tag tuple so repeated requests reuse it; never
 * put a principal, question, docId or chunkId in a tag.
 */
@Component
public class RequestMetrics {

    private final MeterRegistry registry;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();

    public RequestMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void request(String endpoint, String status) {
        counter("rag.requests", "endpoint", endpoint, "status", status).increment();
    }

    public void refusal(String reason) {
        counter("rag.refusals", "reason", reason).increment();
    }

    public void emptyRetrieval(String endpoint, InternalRefusalCause cause) {
        counter("rag.retrieval.empty", "endpoint", endpoint, "cause", cause.wire()).increment();
    }

    public void tokens(String model, Integer prompt, Integer completion) {
        if (prompt != null) {
            counter("rag.tokens", "kind", "prompt", "model", model).increment(prompt);
        }
        if (completion != null) {
            counter("rag.tokens", "kind", "completion", "model", model).increment(completion);
        }
    }

    public void stage(String stage, long ms) {
        timers.computeIfAbsent(stage, s -> Timer.builder("rag.stage.latency").tag("stage", s)
                .publishPercentileHistogram().register(registry)).record(Duration.ofMillis(ms));
    }

    public void auditWriteFailure(String endpoint) {
        counter("rag.audit.write.failures", "endpoint", endpoint).increment();
    }

    private Counter counter(String name, String... tags) {
        StringBuilder key = new StringBuilder(name);
        for (String tag : tags) {
            key.append('|').append(tag);
        }
        return counters.computeIfAbsent(key.toString(), k -> {
            var builder = Counter.builder(name);
            for (int i = 0; i < tags.length; i += 2) {
                builder.tag(tags[i], tags[i + 1]);
            }
            return builder.register(registry);
        });
    }
}
