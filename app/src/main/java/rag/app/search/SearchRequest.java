package rag.app.search;

import rag.app.web.ApiException;

/** {@code POST /v1/search} body. Phase 1 supports only {@code VECTOR} without rerank. */
public record SearchRequest(String query, Integer topK, String mode, Boolean rerank) {

    public static final int DEFAULT_TOP_K = 5;
    public static final int MAX_TOP_K = 50;

    /** Validates and returns the effective topK. */
    public int validatedTopK() {
        if (query == null || query.isBlank()) {
            throw ApiException.invalid("query must not be blank");
        }
        return validateRetrievalOptions(topK, mode, rerank);
    }

    /** Shared by search and ask: topK range, VECTOR-only mode, no rerank. */
    public static int validateRetrievalOptions(Integer topK, String mode, Boolean rerank) {
        int k = topK == null ? DEFAULT_TOP_K : topK;
        if (k < 1 || k > MAX_TOP_K) {
            throw ApiException.invalid("topK must be between 1 and " + MAX_TOP_K);
        }
        if (mode != null && !mode.equals("VECTOR")) {
            throw ApiException.unsupported("mode " + mode + " is not supported yet; use VECTOR");
        }
        if (Boolean.TRUE.equals(rerank)) {
            throw ApiException.unsupported("rerank is not supported yet");
        }
        return k;
    }
}
