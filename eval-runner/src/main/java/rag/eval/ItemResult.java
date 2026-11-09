package rag.eval;

import java.util.List;
import rag.core.eval.GoldenItem;
import rag.core.eval.ItemScores;

/**
 * One scored golden item. Scores come from the first repetition; {@code repetitions} holds the latency of every
 * repetition (spec 04 §7.1), first one first.
 */
public record ItemResult(GoldenItem item, ItemScores scores, List<Latency> repetitions) {

    /** Client-side total plus server-side stage times ({@code null} when the server did not report them). */
    public record Latency(long totalMs, Long retrievalMs, Long rerankMs) {}

    public ItemResult {
        if (repetitions.isEmpty()) {
            throw new IllegalArgumentException("at least one repetition");
        }
        repetitions = List.copyOf(repetitions);
    }

    public ItemResult(GoldenItem item, ItemScores scores, long latencyMs) {
        this(item, scores, List.of(new Latency(latencyMs, null, null)));
    }

    /** Client-side total of the first repetition. */
    public long latencyMs() {
        return repetitions.getFirst().totalMs();
    }
}
