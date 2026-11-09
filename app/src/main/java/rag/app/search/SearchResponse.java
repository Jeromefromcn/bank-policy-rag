package rag.app.search;

import java.util.List;

/**
 * {@code /v1/search} response. {@code diagnostics} values derive only from documents the caller may see (spec 03 §3.2,
 * spec 04 §4): {@code bestVectorSimilarity} is null in {@code BM25} mode, {@code bestRerankProbability} is null
 * without rerank. {@code latencyMs} is server-side stage time.
 */
public record SearchResponse(String queryId, List<SearchHit> hits, Diagnostics diagnostics) {

    public record Diagnostics(Double bestVectorSimilarity, Double bestRerankProbability, Latency latencyMs) {}

    public record Latency(long retrieval, long rerank) {}
}
