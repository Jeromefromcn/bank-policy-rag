package rag.core.rerank;

import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/** Ordering and score mapping for cross-encoder rerank (spec 04 §4, §5). */
public final class RerankOrder {

    private RerankOrder() {}

    /** Indices of {@code scores}, highest score first; equal scores keep their input order. */
    public static List<Integer> order(List<Double> scores) {
        return IntStream.range(0, scores.size()).boxed()
                .sorted(Comparator.comparingDouble((Integer i) -> scores.get(i)).reversed())
                .toList();
    }

    /** Maps a raw logit to (0, 1); monotonic, so it never changes an ordering or a gate decision. */
    public static double sigmoid(double logit) {
        return 1.0 / (1.0 + Math.exp(-logit));
    }
}
