package rag.app.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import rag.app.RagProperties;
import rag.app.rerank.Reranker;
import rag.app.security.Principal;
import rag.core.chunk.TokenCounter;
import rag.core.rerank.RerankOrder;

/**
 * The single retrieval entry point for search and ask (spec 04 §4): mode dispatch and fusion via {@link HybridSearch},
 * then optionally a cross-encoder rerank of the top candidates. Candidates come from the clearance-filtered search,
 * so the reranker only sees what the caller may see. Each candidate is cut to {@code maxPassageTokens} for the
 * reranker only; hits keep their full text.
 */
@Service
public class RetrievalPipeline {

    /**
     * @param bestRerankProbability sigmoid of the best rerank score; null without rerank or with no hits
     */
    public record Result(List<SearchHit> hits, Double bestVectorSimilarity, Double bestRerankProbability, long retrievalMs,
            long rerankMs) {}

    private final HybridSearch search;
    private final Reranker reranker;
    private final TokenCounter tokens;
    private final int candidates;
    private final int maxPassageTokens;

    @Autowired
    public RetrievalPipeline(HybridSearch search, Reranker reranker, RagProperties props) {
        this(search, reranker, TokenCounter.cl100k(), props.rerank().candidates(), props.rerank().maxPassageTokens());
    }

    RetrievalPipeline(HybridSearch search, Reranker reranker, TokenCounter tokens, int candidates, int maxPassageTokens) {
        this.search = search;
        this.reranker = reranker;
        this.tokens = tokens;
        this.candidates = candidates;
        this.maxPassageTokens = maxPassageTokens;
    }

    public Result retrieve(String query, int topK, Mode mode, boolean rerank, Principal principal) {
        long t0 = System.nanoTime();
        if (!rerank) {
            var outcome = search.searchWithDiagnostics(query, topK, mode, principal);
            return new Result(outcome.hits(), outcome.bestVectorSimilarity(), null, ms(t0), 0);
        }
        var outcome = search.searchWithDiagnostics(query, Math.max(candidates, topK), mode, principal);
        long retrievalMs = ms(t0);
        List<SearchHit> pool = outcome.hits();
        if (pool.isEmpty()) {
            return new Result(pool, outcome.bestVectorSimilarity(), null, retrievalMs, 0);
        }

        long t1 = System.nanoTime();
        List<Double> scores = reranker.score(query, pool.stream().map(h -> tokens.truncate(h.text(), maxPassageTokens)).toList());
        List<Integer> order = RerankOrder.order(scores);
        List<SearchHit> out = new ArrayList<>(Math.min(topK, pool.size()));
        for (int i : order.subList(0, Math.min(topK, order.size()))) {
            SearchHit h = pool.get(i);
            double probability = RerankOrder.sigmoid(scores.get(i));
            Map<String, Object> s = new LinkedHashMap<>(h.scores());
            s.put("fusedRank", i + 1);
            s.put("rerankScore", scores.get(i));
            s.put("rerankProbability", probability);
            out.add(new SearchHit(h.chunkId(), h.sectionId(), h.docId(), h.docTitle(), h.sectionPath(), h.coveredSections(),
                    h.pages(), h.classification(), probability, s, h.text()));
        }
        return new Result(out, outcome.bestVectorSimilarity(), out.getFirst().score(), retrievalMs, ms(t1));
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
