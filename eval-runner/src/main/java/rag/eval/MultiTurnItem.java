package rag.eval;

import java.util.List;
import rag.core.eval.ExpectedSource;
import rag.core.model.Clearance;

/**
 * One multi-turn eval item (spec 04 §7.2): earlier turns are golden item ids asked live, then a follow-up scored
 * against {@code expectedSources} (by default those of {@code standaloneGoldenId}, the follow-up's standalone form).
 */
public record MultiTurnItem(String id, String split, List<String> turns, String followUp, String standaloneGoldenId,
        List<ExpectedSource> expectedSources, Clearance minClearance) {

    public MultiTurnItem {
        turns = List.copyOf(turns);
        expectedSources = List.copyOf(expectedSources);
    }
}
