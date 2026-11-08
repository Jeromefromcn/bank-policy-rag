package rag.core.eval;

import java.util.List;

/**
 * Retrieval metrics per spec 00 §6.3. Each expected source counts once, at its highest-ranked
 * match. MRR and nDCG look at the top 10; nDCG uses binary gain per newly matched expected source.
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
        for (ExpectedSource e : expected) {
            for (int r = 1; r <= depth; r++) {
                if (SourceMatcher.matches(e, ranked.get(r - 1))) {
                    if (r <= 5) {
                        found5++;
                    }
                    found10++;
                    dcg += 1 / log2(r + 1);
                    first = first == null ? r : Math.min(first, r);
                    break;
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
