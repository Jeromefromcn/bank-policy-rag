package rag.core.retrieve;

import java.util.Map;

/** A fused result: the id, its RRF score, and its 1-based rank in each leg that returned it (keyed by leg index). */
public record RankedId(String id, double score, Map<Integer, Integer> legRanks) {

    public RankedId {
        legRanks = Map.copyOf(legRanks);
    }

    int bestLegRank() {
        return legRanks.values().stream().mapToInt(Integer::intValue).min().orElse(Integer.MAX_VALUE);
    }
}
