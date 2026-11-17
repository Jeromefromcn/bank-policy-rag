package rag.app.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.search.Hit;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import rag.app.RagProperties;
import rag.app.embed.Embedder;
import rag.app.es.ChunkDocument;
import rag.app.security.Principal;
import rag.core.model.Clearance;

/**
 * kNN retrieval over the chunks index (the alias unless {@code rag.retrieval.chunks-index} says otherwise). The classification filter sits inside the kNN clause, so it
 * is applied during the HNSW search (pre-filter), never afterwards (ROADMAP §4.2).
 */
@Component
public class VectorRetriever {

    private final ElasticsearchClient client;
    private final Embedder embedder;
    private final RagProperties props;

    public VectorRetriever(ElasticsearchClient client, Embedder embedder, RagProperties props) {
        this.client = client;
        this.embedder = embedder;
        this.props = props;
    }

    /** Best-first hits; {@code scores} carries {@code vectorRank} and the raw cosine {@code similarity}. */
    public List<SearchHit> search(String query, int topK, Principal principal) {
        float[] vector = embedder.embedQuery(query);
        List<Float> queryVector = new ArrayList<>(vector.length);
        for (float f : vector) {
            queryVector.add(f);
        }
        List<FieldValue> visible = principal.clearance().visible().stream()
                .sorted().map(Clearance::wire).map(FieldValue::of).toList();
        int numCandidates = Math.max(props.retrieval().numCandidates(), topK);

        List<Hit<ChunkDocument>> hits;
        try {
            hits = client.search(s -> s.index(props.retrieval().chunksTarget(props.envPrefix()))
                            .size(topK)
                            .knn(k -> k.field("embedding").queryVector(queryVector).k(topK).numCandidates(numCandidates)
                                    .filter(f -> f.terms(t -> t.field("classification").terms(v -> v.value(visible)))))
                            .source(src -> src.filter(f -> f.excludes("embedding"))),
                    ChunkDocument.class).hits().hits();
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                return List.of();   // no index yet: nothing to retrieve
            }
            throw e;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        List<SearchHit> out = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            Hit<ChunkDocument> h = hits.get(i);
            ChunkDocument d = h.source();
            double score = h.score() == null ? 0 : h.score();
            Map<String, Object> scores = new LinkedHashMap<>();
            scores.put("vectorRank", i + 1);
            // ES reports cosine kNN scores as (1 + cosine) / 2.
            scores.put("similarity", 2 * score - 1);
            out.add(new SearchHit(d.chunkId(), d.sectionId(), d.docId(), d.docTitle(), d.sectionPath(), d.coveredSections(),
                    d.pages(), d.classification(), score, scores, d.content(), d.contentHash()));
        }
        return out;
    }
}
