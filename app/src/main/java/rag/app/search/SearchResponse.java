package rag.app.search;

import java.util.List;

/**
 * {@code /v1/search} response. {@code diagnostics.bestVectorSimilarity} is derived only from documents the caller
 * may see (spec 03 §3.2); it is null in {@code BM25} mode.
 */
public record SearchResponse(String queryId, List<SearchHit> hits, Diagnostics diagnostics) {

    public record Diagnostics(Double bestVectorSimilarity) {}
}
