package rag.app.search;

import rag.app.web.ApiException;

/** {@code POST /v1/search} body. {@code rerank} is rejected until Phase 4. */
public record SearchRequest(String query, Integer topK, String mode, Boolean rerank) {

    public static final int DEFAULT_TOP_K = 5;
    public static final int MAX_TOP_K = 50;

    /** The validated retrieval options shared by search and ask. */
    public record Options(int topK, Mode mode) {}

    public Options validated() {
        if (query == null || query.isBlank()) {
            throw ApiException.invalid("query must not be blank");
        }
        return validateRetrievalOptions(topK, mode, rerank);
    }

    /** Shared by search and ask: topK range, known mode (default HYBRID), no rerank. */
    public static Options validateRetrievalOptions(Integer topK, String mode, Boolean rerank) {
        int k = topK == null ? DEFAULT_TOP_K : topK;
        if (k < 1 || k > MAX_TOP_K) {
            throw ApiException.invalid("topK must be between 1 and " + MAX_TOP_K);
        }
        Mode parsed = Mode.parse(mode);
        if (Boolean.TRUE.equals(rerank)) {
            throw ApiException.unsupported("rerank is not supported yet");
        }
        return new Options(k, parsed);
    }
}
