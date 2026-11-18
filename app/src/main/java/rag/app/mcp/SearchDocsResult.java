package rag.app.mcp;

import java.util.List;
import rag.app.search.SearchHit;

/** Lean {@code search_docs} payload (spec 06 §3.3): the fields an agent needs to cite and to expand with get_section. */
public record SearchDocsResult(String query, List<Hit> hits) {

    public record Hit(String chunkId, String sectionId, String docId, String docTitle, List<String> sectionPath,
            List<Integer> pages, String classification, double score, String text) {}

    public static SearchDocsResult from(String query, List<SearchHit> hits) {
        return new SearchDocsResult(query, hits.stream().map(h -> new Hit(h.chunkId(), h.sectionId(), h.docId(),
                h.docTitle(), h.sectionPath(), h.pages(), h.classification(), h.score(), h.text())).toList());
    }
}
