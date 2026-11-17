package rag.app.metrics;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/** Starts a span, tags it, runs the body and records an error. Tags are low cardinality only. */
public final class Observations {

    private Observations() {}

    public static <T> T observe(ObservationRegistry registry, String name, Map<String, String> tags, Supplier<T> body) {
        return observe(registry, name, tags, ignored -> Map.of(), body);
    }

    /** {@code resultTags} runs after the body so tags such as {@code hits} or {@code returned} can be added. */
    public static <T> T observe(ObservationRegistry registry, String name, Map<String, String> tags,
            Function<T, Map<String, String>> resultTags, Supplier<T> body) {
        Observation observation = Observation.createNotStarted(name, registry);
        tags.forEach(observation::lowCardinalityKeyValue);
        observation.start();
        try {
            T result = body.get();
            resultTags.apply(result).forEach(observation::lowCardinalityKeyValue);
            return result;
        } catch (RuntimeException e) {
            observation.error(e);
            throw e;
        } finally {
            observation.stop();
        }
    }
}
