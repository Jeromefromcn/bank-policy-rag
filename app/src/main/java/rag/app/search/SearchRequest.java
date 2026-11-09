package rag.app.search;

import rag.app.web.ApiException;

/** {@code POST /v1/search} body. {@code rerank} (default false) enables cross-encoder rerank (spec 04 §4). */
public record SearchRequest(String query, Integer topK, String mode, Boolean rerank) {

    public static final int DEFAULT_TOP_K = 5;
    public static final int MAX_TOP_K = 50;

    /** The validated retrieval options shared by search and ask. */
    public record Options(int topK, Mode mode, boolean rerank) {}

    public Options validated() {
        if (query == null || query.isBlank()) {
            throw ApiException.invalid("query must not be blank");
        }
        return validateRetrievalOptions(topK, mode, rerank);
    }

    /** Shared by search and ask: topK range, known mode (default HYBRID), rerank default false. */
    public static Options validateRetrievalOptions(Integer topK, String mode, Boolean rerank) {
        int k = topK == null ? DEFAULT_TOP_K : topK;
        if (k < 1 || k > MAX_TOP_K) {
            throw ApiException.invalid("topK must be between 1 and " + MAX_TOP_K);
        }
        return new Options(k, Mode.parse(mode), Boolean.TRUE.equals(rerank));
    }
}
