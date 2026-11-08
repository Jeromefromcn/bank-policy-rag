package rag.app.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.stereotype.Component;

/** Bulk-writes chunk documents with {@code _id = chunkId}; any item error fails the whole write. */
@Component
public class ChunkWriter {

    static final int BATCH = 200;

    private final ElasticsearchClient client;

    public ChunkWriter(ElasticsearchClient client) {
        this.client = client;
    }

    public void bulkIndex(String index, List<ChunkDocument> docs) {
        try {
            for (int i = 0; i < docs.size(); i += BATCH) {
                List<BulkOperation> ops = docs.subList(i, Math.min(docs.size(), i + BATCH)).stream()
                        .map(d -> BulkOperation.of(o -> o.index(x -> x.index(index).id(d.chunkId()).document(d))))
                        .toList();
                BulkResponse res = client.bulk(b -> b.operations(ops));
                if (res.errors()) {
                    var firstError = res.items().stream().filter(it -> it.error() != null).findFirst().orElseThrow();
                    throw new IllegalStateException("bulk write to " + index + " failed for " + firstError.id() + ": "
                            + firstError.error().reason());
                }
            }
            client.indices().refresh(r -> r.index(index));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
