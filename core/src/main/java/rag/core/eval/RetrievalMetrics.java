package rag.core.eval;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Retrieval metrics per spec 00 §6.3. Each expected source counts once, at its highest-ranked
 * match. MRR and nDCG look at the top 10; nDCG gives binary gain to each rank that newly matches at
 * least one expected source.
 */
public final class RetrievalMetrics {

    static final int DEPTH = 10;

    private RetrievalMetrics() {}

    public static ItemScores score(List<ExpectedSource> expected, List<RankedHit> ranked) {
        if (expected.isEmpty()) {
            throw new IllegalArgumentException("item has no expected sources");
        }
        int depth = Math.min(DEPTH, ranked.size());
        int found5 = 0;
        int found10 = 0;
        double dcg = 0;
        Integer first = null;
        Set<ExpectedSource> matched = new HashSet<>();
        for (int r = 1; r <= depth; r++) {
            RankedHit hit = ranked.get(r - 1);
            boolean newMatch = false;
            for (ExpectedSource e : expected) {
                if (!matched.contains(e) && SourceMatcher.matches(e, hit)) {
                    matched.add(e);
                    newMatch = true;
                    found10++;
                    if (r <= 5) {
                        found5++;
                    }
                }
            }
            if (newMatch) {
                // Binary gain per rank: a chunk covering several expected sources still earns 1,
                // so nDCG stays within [0, 1] against the ideal below.
                dcg += 1 / log2(r + 1);
                if (first == null) {
                    first = r;
                }
            }
        }
        double idcg = 0;
        for (int i = 1; i <= Math.min(expected.size(), DEPTH); i++) {
            idcg += 1 / log2(i + 1);
        }
        int n = expected.size();
        return new ItemScores((double) found5 / n, (double) found10 / n, first == null ? 0 : 1.0 / first, dcg / idcg, first);
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }
}
