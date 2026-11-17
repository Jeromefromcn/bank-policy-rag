package rag.core.eval;

import java.util.Set;

/** The CI gate's corpus scope (spec 05 §7.2): Example Bank documents only, no SPM. */
public final class EvalScope {

    private EvalScope() {}

    /** An item is in scope when it has no expected sources (unanswerable) or every source is Example Bank. */
    public static boolean inExampleBank(GoldenItem item, Set<String> exampleBankDocIds) {
        return item.expectedSources().isEmpty()
                || item.expectedSources().stream().allMatch(s -> exampleBankDocIds.contains(s.docId()));
    }
}
