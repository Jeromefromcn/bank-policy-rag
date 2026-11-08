package rag.core.eval;

import java.util.List;

/** The parts of a retrieved chunk that scoring needs. {@code sectionPath} is the number chain. */
public record RankedHit(String docId, List<String> sectionPath, List<String> coveredSections) {

    public RankedHit {
        sectionPath = List.copyOf(sectionPath);
        coveredSections = List.copyOf(coveredSections);
    }
}
