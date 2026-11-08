package rag.app.search;

import java.util.List;
import java.util.Map;

/** One retrieved chunk as returned by {@code /v1/search} (ROADMAP §3.2 plus {@code coveredSections}). */
public record SearchHit(
        String chunkId,
        String sectionId,
        String docId,
        String docTitle,
        List<String> sectionPath,
        List<String> coveredSections,
        List<Integer> pages,
        String classification,
        double score,
        Map<String, Object> scores,
        String text) {}
