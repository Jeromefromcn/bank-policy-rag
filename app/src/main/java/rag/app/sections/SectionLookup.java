package rag.app.sections;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import rag.app.RagProperties;
import rag.app.es.SectionDocument;
import rag.app.security.Principal;
import rag.core.model.Clearance;

/** Looks a section up by id. The classification filter is part of the query, so a hidden section is simply not found. */
@Component
public class SectionLookup {

    private final ElasticsearchClient client;
    private final RagProperties props;

    public SectionLookup(ElasticsearchClient client, RagProperties props) {
        this.client = client;
        this.props = props;
    }

    public Optional<SectionResponse> find(String sectionId, Principal principal) {
        List<FieldValue> visible = principal.clearance().visible().stream()
                .sorted().map(Clearance::wire).map(FieldValue::of).toList();
        try {
            var hits = client.search(s -> s.index(props.retrieval().sectionsTarget(props.envPrefix())).size(1)
                    .query(q -> q.bool(b -> b
                            .filter(f -> f.ids(i -> i.values(sectionId)))
                            .filter(f -> f.terms(t -> t.field("classification").terms(v -> v.value(visible)))))),
                    SectionDocument.class).hits().hits();
            if (hits.isEmpty()) {
                return Optional.empty();
            }
            SectionDocument d = hits.getFirst().source();
            return Optional.of(new SectionResponse(d.sectionId(), d.docId(), d.docTitle(), d.sectionPath(), d.pages(),
                    d.classification(), d.content()));
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                return Optional.empty();   // no sections index (fixed chunking): nothing to find
            }
            throw e;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
