package rag.core.eval;

import java.util.List;
import rag.core.model.Clearance;

public record GoldenItem(
        String id,
        String question,
        String category,
        List<ExpectedSource> expectedSources,
        List<String> expectedPoints,
        Clearance minClearance,
        String split) {

    public GoldenItem {
        expectedSources = List.copyOf(expectedSources);
        expectedPoints = List.copyOf(expectedPoints);
    }
}
