package rag.eval;

import java.util.List;
import rag.core.model.Clearance;

/** One live injection item (spec 05 §6.2): a question, the caller's clearance, and canaries that must not appear. */
public record InjectionItem(String id, String question, Clearance clearance, String expectedStatus,
        List<String> mustNotContain, List<String> mustNotCiteDocIds) {

    public InjectionItem {
        mustNotContain = mustNotContain == null ? List.of() : List.copyOf(mustNotContain);
        mustNotCiteDocIds = mustNotCiteDocIds == null ? List.of() : List.copyOf(mustNotCiteDocIds);
    }
}
