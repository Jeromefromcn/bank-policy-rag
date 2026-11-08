package rag.core.retrieve;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal rank fusion (spec 02 §4): {@code score(d) = Σ 1 / (k + rank_leg(d))} over the legs where
 * {@code d} appears. Ties are broken by the best single-leg rank, then by id, so output is deterministic.
 */
public final class Rrf {

    private Rrf() {}

    /** @param legs best-first id lists, one per retrieval leg; a repeated id counts at its first rank only */
    public static List<RankedId> fuse(List<List<String>> legs, int k, int topK) {
        Map<String, Map<Integer, Integer>> ranks = new LinkedHashMap<>();
        for (int leg = 0; leg < legs.size(); leg++) {
            List<String> ids = legs.get(leg);
            for (int i = 0; i < ids.size(); i++) {
                ranks.computeIfAbsent(ids.get(i), x -> new LinkedHashMap<>()).putIfAbsent(leg, i + 1);
            }
        }
        List<RankedId> fused = new ArrayList<>(ranks.size());
        ranks.forEach((id, legRanks) -> {
            double score = 0;
            for (int rank : legRanks.values()) {
                score += 1.0 / (k + rank);
            }
            fused.add(new RankedId(id, score, legRanks));
        });
        fused.sort(Comparator.comparingDouble(RankedId::score).reversed()
                .thenComparingInt(RankedId::bestLegRank)
                .thenComparing(RankedId::id));
        return fused.size() > topK ? new ArrayList<>(fused.subList(0, topK)) : fused;
    }
}
