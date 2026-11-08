package rag.app.search;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import rag.app.RagProperties;
import rag.app.security.Principal;
import rag.core.retrieve.RankedId;
import rag.core.retrieve.Rrf;

/**
 * Mode dispatch and application-layer reciprocal rank fusion (spec 02 §4). {@code HYBRID} asks each leg
 * for {@code max(legSize, topK)} hits, fuses with k = 60 and truncates to {@code topK}. A failing leg
 * fails the request: if the embedder is down, {@code HYBRID} does not quietly become {@code BM25}.
 */
@Service
public class HybridSearch {

    static final int RRF_K = 60;
    private static final int VECTOR = 0;
    private static final int BM25 = 1;

    private final VectorRetriever vector;
    private final Bm25Retriever bm25;
    private final int legSize;

    @Autowired
    public HybridSearch(VectorRetriever vector, Bm25Retriever bm25, RagProperties props) {
        this(vector, bm25, props.retrieval().legSize());
    }

    HybridSearch(VectorRetriever vector, Bm25Retriever bm25, int legSize) {
        this.vector = vector;
        this.bm25 = bm25;
        this.legSize = legSize;
    }

    public List<SearchHit> search(String query, int topK, Mode mode, Principal principal) {
        return switch (mode) {
            case VECTOR -> vector.search(query, topK, principal);
            case BM25 -> bm25.search(query, topK, principal);
            case HYBRID -> hybrid(query, topK, principal);
        };
    }

    private List<SearchHit> hybrid(String query, int topK, Principal principal) {
        int size = Math.max(legSize, topK);
        List<SearchHit> vectorHits = vector.search(query, size, principal);
        List<SearchHit> bm25Hits = bm25.search(query, size, principal);

        Map<String, SearchHit> byId = new HashMap<>();
        Map<String, SearchHit> vectorById = new HashMap<>();
        Map<String, SearchHit> bm25ById = new HashMap<>();
        for (SearchHit h : vectorHits) {
            vectorById.putIfAbsent(h.chunkId(), h);
            byId.putIfAbsent(h.chunkId(), h);
        }
        for (SearchHit h : bm25Hits) {
            bm25ById.putIfAbsent(h.chunkId(), h);
            byId.putIfAbsent(h.chunkId(), h);
        }

        List<RankedId> fused = Rrf.fuse(
                List.of(vectorHits.stream().map(SearchHit::chunkId).toList(), bm25Hits.stream().map(SearchHit::chunkId).toList()),
                RRF_K, topK);

        List<SearchHit> out = new ArrayList<>(fused.size());
        for (RankedId r : fused) {
            SearchHit base = byId.get(r.id());
            Map<String, Object> scores = new LinkedHashMap<>();
            SearchHit v = vectorById.get(r.id());
            SearchHit b = bm25ById.get(r.id());
            if (v != null) {
                scores.put("vectorRank", r.legRanks().get(VECTOR));
                scores.put("similarity", v.scores().get("similarity"));
            }
            if (b != null) {
                scores.put("bm25Rank", r.legRanks().get(BM25));
                scores.put("bm25Score", b.scores().get("bm25Score"));
            }
            out.add(new SearchHit(base.chunkId(), base.sectionId(), base.docId(), base.docTitle(), base.sectionPath(),
                    base.coveredSections(), base.pages(), base.classification(), r.score(), scores, base.text()));
        }
        return out;
    }
}
