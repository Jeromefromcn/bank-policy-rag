package rag.app.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.stereotype.Component;

/** Bulk-writes section documents with {@code _id = sectionId}; any item error fails the whole write. */
@Component
public class SectionWriter {

    private final ElasticsearchClient client;

    public SectionWriter(ElasticsearchClient client) {
        this.client = client;
    }

    public void bulkIndex(String index, List<SectionDocument> docs) {
        try {
            List<BulkOperation> ops = docs.stream()
                    .map(d -> BulkOperation.of(o -> o.index(x -> x.index(index).id(d.sectionId()).document(d))))
                    .toList();
            BulkResponse res = client.bulk(b -> b.operations(ops));
            if (res.errors()) {
                var firstError = res.items().stream().filter(it -> it.error() != null).findFirst().orElseThrow();
                throw new IllegalStateException("bulk write to " + index + " failed for " + firstError.id() + ": "
                        + firstError.error().reason());
            }
            client.indices().refresh(r -> r.index(index));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
