package rag.app.search;

import rag.app.web.ApiException;

/** Retrieval mode of {@code /v1/search} and {@code /v1/ask} (ROADMAP §3.2). */
public enum Mode {
    VECTOR, BM25, HYBRID;

    /** {@code null} means the default, {@link #HYBRID}. Matching is exact and case-sensitive, like the wire contract. */
    public static Mode parse(String raw) {
        if (raw == null) {
            return HYBRID;
        }
        for (Mode m : values()) {
            if (m.name().equals(raw)) {
                return m;
            }
        }
        throw ApiException.unsupported("mode " + raw + " is not supported; use VECTOR, BM25 or HYBRID");
    }
}
