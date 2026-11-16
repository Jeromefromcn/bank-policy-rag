package rag.app.audit;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.OpType;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import rag.app.RagProperties;
import rag.app.web.ApiException;
import rag.app.web.ProblemTypes;

/**
 * Append-only writer (spec 05 §4.1, §4.3): {@code op_type=create} with the response id as {@code _id}, so a
 * retry cannot create a second record. Any failure becomes a 503; the caller must not return the answer.
 */
@Component
public class AuditStore {

    private final ElasticsearchClient client;
    private final RagProperties props;

    public AuditStore(ElasticsearchClient client, RagProperties props) {
        this.client = client;
        this.props = props;
    }

    public void write(AuditRecord record) {
        if (record == null || record.id() == null || record.id().isBlank()) {
            throw new IllegalArgumentException("audit record id is required");
        }
        try {
            client.index(i -> i.index(props.auditIndex()).id(record.id()).opType(OpType.Create).document(record));
        } catch (ElasticsearchException | IOException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ProblemTypes.AUDIT_UNAVAILABLE,
                    "audit record could not be written: " + e.getMessage());
        }
    }
}
