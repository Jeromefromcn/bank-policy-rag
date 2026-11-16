package rag.app.audit;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;
import rag.app.RagProperties;
import rag.app.security.Principal;
import rag.core.model.Clearance;

/**
 * Count-only, unfiltered probe run after a clearance-filtered retrieval returns nothing (spec 05 §3.2).
 * It asks whether documents above the caller's clearance would have matched; the result goes to the audit
 * record and metrics only, never to the caller.
 */
@Component
public class VisibilityProbe {

    private final ElasticsearchClient client;
    private final RagProperties props;

    public VisibilityProbe(ElasticsearchClient client, RagProperties props) {
        this.client = client;
        this.props = props;
    }

    public InternalRefusalCause cause(String query, Principal principal) {
        List<FieldValue> hidden = Arrays.stream(Clearance.values())
                .filter(c -> !principal.clearance().visible().contains(c))
                .sorted()
                .map(Clearance::wire)
                .map(FieldValue::of)
                .toList();
        if (hidden.isEmpty()) {
            return InternalRefusalCause.NO_MATCHING_CONTENT;
        }
        try {
            long count = client.count(c -> c.index(props.retrieval().chunksTarget(props.envPrefix()))
                    .query(q -> q.bool(b -> b
                            .must(m -> m.multiMatch(mm -> mm.query(query).fields("content", "docTitle")))
                            .filter(f -> f.terms(t -> t.field("classification").terms(v -> v.value(hidden)))))))
                    .count();
            return count > 0 ? InternalRefusalCause.NO_ACCESSIBLE_SOURCES : InternalRefusalCause.NO_MATCHING_CONTENT;
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                return InternalRefusalCause.NO_MATCHING_CONTENT;
            }
            throw e;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
