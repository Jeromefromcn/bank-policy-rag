package rag.app.audit;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import rag.app.RagProperties;
import tools.jackson.databind.json.JsonMapper;

/**
 * Creates the audit index with an explicit strict mapping (spec 05 §4.1). It attempts the create directly
 * and treats an already-existing index as success: the app's audit grant is {@code create_doc} +
 * {@code create_index}, and a HEAD/metadata check may need {@code view_index_metadata}.
 */
@Component
public class AuditIndexAdmin {

    private static final Logger LOG = LoggerFactory.getLogger(AuditIndexAdmin.class);
    private final ElasticsearchClient client;
    private final RagProperties props;
    private final JsonMapper json = JsonMapper.builder().build();

    public AuditIndexAdmin(ElasticsearchClient client, RagProperties props) {
        this.client = client;
        this.props = props;
    }

    /** Creates the index if needed; throws on any other failure. */
    public void ensure() {
        String index = props.auditIndex();
        try {
            client.indices().create(CreateIndexRequest.of(b -> b.index(index).withJson(new StringReader(mapping()))));
            LOG.info("created audit index {}", index);
        } catch (ElasticsearchException e) {
            if (e.error() != null && "resource_already_exists_exception".equals(e.error().type())) {
                return;
            }
            throw e;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String mapping() {
        Map<String, Object> keyword = Map.of("type", "keyword");
        Map<String, Object> integer = Map.of("type", "integer");
        Map<String, Object> textNotIndexed = Map.of("type", "text", "index", false);

        Map<String, Object> hit = new LinkedHashMap<>();
        for (String f : List.of("chunkId", "contentHash", "docId", "classification")) {
            hit.put(f, keyword);
        }
        hit.put("rank", integer);
        hit.put("score", Map.of("type", "double"));
        hit.put("sectionPath", keyword);

        Map<String, Object> retrieval = new LinkedHashMap<>();
        retrieval.put("mode", keyword);
        retrieval.put("topK", integer);
        retrieval.put("rerank", Map.of("type", "boolean"));
        retrieval.put("bestVectorSimilarity", Map.of("type", "double"));
        retrieval.put("bestRerankProbability", Map.of("type", "double"));
        retrieval.put("hits", Map.of("type", "object", "properties", hit));

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("provider", keyword);
        model.put("id", keyword);
        model.put("temperature", Map.of("type", "double"));
        model.put("maxTokens", integer);

        Map<String, Object> citation = new LinkedHashMap<>();
        citation.put("ref", integer);
        citation.put("chunkId", keyword);
        citation.put("docId", keyword);
        citation.put("sectionPath", keyword);
        citation.put("pages", integer);
        citation.put("quote", textNotIndexed);

        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("promptTokens", integer);
        usage.put("completionTokens", integer);

        Map<String, Object> latency = new LinkedHashMap<>();
        for (String f : List.of("retrieval", "rerank", "generation", "rewrite", "audit")) {
            latency.put(f, Map.of("type", "long"));
        }

        Map<String, Object> props = new LinkedHashMap<>();
        for (String f : List.of("answerId", "queryId", "traceId", "principal", "clearance", "endpoint",
                "promptTemplateVersion", "status", "refusalReason", "internalRefusalCause", "corpusVersion",
                "chunkerVersion", "embeddingModel", "replayOf")) {
            props.put(f, keyword);
        }
        for (String f : List.of("question", "rewrittenQuery", "renderedPrompt", "answer")) {
            props.put(f, textNotIndexed);
        }
        props.put("timestamp", Map.of("type", "date"));
        props.put("retrieval", Map.of("type", "object", "properties", retrieval));
        props.put("model", Map.of("type", "object", "properties", model));
        props.put("citations", Map.of("type", "object", "properties", citation));
        props.put("usage", Map.of("type", "object", "properties", usage));
        props.put("latencyMs", Map.of("type", "object", "properties", latency));

        return json.writeValueAsString(Map.of(
                "settings", Map.of("number_of_replicas", 0),
                "mappings", Map.of("dynamic", "strict", "properties", props)));
    }
}
