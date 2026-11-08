package rag.app.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.search.Hit;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import rag.app.RagProperties;
import rag.app.es.ChunkDocument;
import rag.core.model.Clearance;
import rag.app.security.Principal;

/**
 * BM25 retrieval over {@code content} and {@code docTitle} (spec 02 §4). {@code docTitle} is searched
 * because header stripping removes module codes such as {@code OR-2} from the body. The classification
 * filter is in {@code bool.filter}, so it applies before scoring and top-k (ROADMAP §4.2).
 */
@Component
public class Bm25Retriever {

    private final ElasticsearchClient client;
    private final RagProperties props;

    public Bm25Retriever(ElasticsearchClient client, RagProperties props) {
        this.client = client;
        this.props = props;
    }

    /** Best-first hits; {@code scores} carries {@code bm25Rank} and the raw {@code bm25Score}. */
    public List<SearchHit> search(String query, int size, Principal principal) {
        List<FieldValue> visible = principal.clearance().visible().stream()
                .sorted().map(Clearance::wire).map(FieldValue::of).toList();

        List<Hit<ChunkDocument>> hits;
        try {
            hits = client.search(s -> s.index(props.retrieval().chunksTarget(props.envPrefix()))
                            .size(size)
                            .query(q -> q.bool(b -> b
                                    .must(m -> m.multiMatch(mm -> mm.query(query).fields("content", "docTitle").type(TextQueryType.BestFields)))
                                    .filter(f -> f.terms(t -> t.field("classification").terms(v -> v.value(visible))))))
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
            scores.put("bm25Rank", i + 1);
            scores.put("bm25Score", score);
            out.add(new SearchHit(d.chunkId(), d.sectionId(), d.docId(), d.docTitle(), d.sectionPath(), d.coveredSections(),
                    d.pages(), d.classification(), score, scores, d.content()));
        }
        return out;
    }
}
