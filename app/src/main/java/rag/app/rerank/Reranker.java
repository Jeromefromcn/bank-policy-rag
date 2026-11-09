package rag.app.rerank;

import java.util.List;

/** Cross-encoder relevance scores (spec 04 §3, §4). */
public interface Reranker {

    /**
     * One raw score (logit) per passage, in input order. Higher is more relevant.
     *
     * @throws RerankUnavailableException if the reranker cannot be reached or answers with anything unusable
     */
    List<Double> score(String query, List<String> passages);
}
