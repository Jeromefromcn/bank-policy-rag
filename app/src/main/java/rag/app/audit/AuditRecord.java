package rag.app.audit;

import java.util.List;

/**
 * One completed {@code /v1/ask} or {@code /v1/search} response (spec 05 §4.2). Audited fields are exactly
 * ROADMAP §4.3 plus {@code internalRefusalCause}; {@code answerId} is set for `/ask` and {@code queryId}
 * for `/search`. Records are written once and never updated.
 */
public record AuditRecord(
        String answerId,
        String queryId,
        String timestamp,
        String traceId,
        String principal,
        String clearance,
        String endpoint,
        String question,
        String rewrittenQuery,
        Retrieval retrieval,
        String promptTemplateVersion,
        String renderedPrompt,
        Model model,
        String answer,
        List<Citation> citations,
        String status,
        String refusalReason,
        String internalRefusalCause,
        Usage usage,
        Latency latencyMs,
        String corpusVersion,
        String chunkerVersion,
        String embeddingModel) {

    public AuditRecord {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    /** The Elasticsearch document id: the answer id for `/ask`, the query id for `/search`. */
    public String id() {
        return answerId != null ? answerId : queryId;
    }

    public record Retrieval(String mode, int topK, boolean rerank, Double bestVectorSimilarity,
            Double bestRerankProbability, List<Hit> hits) {
        public Retrieval {
            hits = hits == null ? List.of() : List.copyOf(hits);
        }
    }

    public record Hit(String chunkId, String contentHash, int rank, double score, String docId,
            List<String> sectionPath, String classification) {
        public Hit {
            sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
        }
    }

    public record Model(String provider, String id, Double temperature, Integer maxTokens) {}

    public record Citation(int ref, String chunkId, String docId, List<String> sectionPath, List<Integer> pages,
            String quote) {
        public Citation {
            sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
            pages = pages == null ? List.of() : List.copyOf(pages);
        }
    }

    public record Usage(Integer promptTokens, Integer completionTokens) {}

    public record Latency(long retrieval, long rerank, long generation, long rewrite, long audit) {}
}
