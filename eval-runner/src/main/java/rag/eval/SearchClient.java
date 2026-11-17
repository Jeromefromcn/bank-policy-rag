package rag.eval;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import rag.core.eval.RankedHit;
import tools.jackson.databind.JsonNode;

/**
 * Calls the app over HTTP with a real API key, so evals exercise authentication and filtering.
 *
 * @param bestVectorSimilarity  {@code diagnostics.bestVectorSimilarity}, null when absent (BM25 mode, empty vector leg)
 * @param textByChunkId         chunk text keyed by id; used for judging only and never written to a committed report
 * @param bestRerankProbability {@code diagnostics.bestRerankProbability}, null without rerank
 * @param serverRetrievalMs     {@code diagnostics.latencyMs.retrieval}, null when the server does not report it
 * @param serverRerankMs        {@code diagnostics.latencyMs.rerank}, likewise
 */
public class SearchClient {

    public record SearchResult(List<RankedHit> hits, long latencyMs, Double bestVectorSimilarity, Map<String, String> textByChunkId,
            Double bestRerankProbability, Long serverRetrievalMs, Long serverRerankMs) {}

    private final ApiHttp http;
    private final String mode;
    private final boolean rerank;

    public SearchClient(URI base, String apiKey, String mode) {
        this(base, apiKey, mode, false);
    }

    public SearchClient(URI base, String apiKey, String mode, boolean rerank) {
        this.http = new ApiHttp(base, apiKey);
        this.mode = mode;
        this.rerank = rerank;
    }

    public SearchResult search(String query, int topK) {
        long t0 = System.nanoTime();
        JsonNode res = http.post("/v1/search", Map.of("query", query, "topK", topK, "mode", mode, "rerank", rerank));
        long latency = (System.nanoTime() - t0) / 1_000_000;
        List<RankedHit> hits = new ArrayList<>();
        Map<String, String> text = new LinkedHashMap<>();
        for (JsonNode h : res.get("hits")) {
            hits.add(new RankedHit(h.get("docId").asString(), strings(h.get("sectionPath")), strings(h.get("coveredSections"))));
            if (h.has("chunkId") && h.has("text")) {
                text.put(h.get("chunkId").asString(), h.get("text").asString());
            }
        }
        JsonNode diag = res.path("diagnostics");
        return new SearchResult(hits, latency, number(diag.path("bestVectorSimilarity")), text,
                number(diag.path("bestRerankProbability")), whole(diag.path("latencyMs").path("retrieval")),
                whole(diag.path("latencyMs").path("rerank")));
    }

    /** The version map from {@code /v1/info}: corpus, chunker and embedding versions. */
    public Map<String, String> info() {
        JsonNode rag = http.get("/v1/info");
        Map<String, String> out = new LinkedHashMap<>();
        if (rag != null) {
            rag.properties().forEach(e -> out.put(e.getKey(), e.getValue().asString()));
        }
        return out;
    }

    private static Double number(JsonNode n) {
        return n.isNumber() ? n.asDouble() : null;
    }

    private static Long whole(JsonNode n) {
        return n.isNumber() ? n.asLong() : null;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null) {
            array.forEach(n -> out.add(n.asString()));
        }
        return out;
    }
}
