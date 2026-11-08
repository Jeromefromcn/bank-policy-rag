package rag.app.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Creates, inspects and deletes chunk indices and moves the alias. */
@Component
public class ChunkIndexAdmin {

    /** The physical index behind an alias and that index's {@code _meta}. */
    public record AliasInfo(String index, Map<String, String> meta) {}

    static final int HNSW_M = 16;
    static final int HNSW_EF_CONSTRUCTION = 100;

    private final ElasticsearchClient client;
    private final JsonMapper json = JsonMapper.builder().build();

    public ChunkIndexAdmin(ElasticsearchClient client) {
        this.client = client;
    }

    public boolean exists(String index) {
        try {
            return client.indices().exists(e -> e.index(index)).value();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void create(String index, int dims, Map<String, String> meta) {
        String body = json.writeValueAsString(Map.of(
                "settings", Map.of("number_of_replicas", 0),
                "mappings", mapping(dims, meta)));
        try {
            client.indices().create(CreateIndexRequest.of(b -> b.index(index).withJson(new StringReader(body))));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Creates the {@code sections} index: strict mapping, no vector field. */
    public void createSections(String index, Map<String, String> meta) {
        Map<String, Object> kw = Map.of("type", "keyword");
        Map<String, Object> props = new LinkedHashMap<>();
        for (String f : List.of("sectionId", "docId", "docVersion", "sourceType", "sectionPath", "classification", "corpusVersion")) {
            props.put(f, kw);
        }
        props.put("docTitle", Map.of("type", "text", "fields", Map.of("keyword", kw)));
        props.put("pages", Map.of("type", "integer"));
        props.put("content", Map.of("type", "text", "analyzer", "english"));
        String body = json.writeValueAsString(Map.of(
                "settings", Map.of("number_of_replicas", 0),
                "mappings", Map.of("dynamic", "strict", "_meta", meta, "properties", props)));
        try {
            client.indices().create(CreateIndexRequest.of(b -> b.index(index).withJson(new StringReader(body))));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void delete(String index) {
        try {
            client.indices().delete(d -> d.index(index));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Points {@code alias} at {@code index} only, removing it from any other index in one call. */
    public void pointAlias(String alias, String index) {
        List<String> current = aliasTargets(alias);
        try {
            client.indices().updateAliases(u -> {
                for (String old : current) {
                    if (!old.equals(index)) {
                        u.actions(a -> a.remove(r -> r.index(old).alias(alias)));
                    }
                }
                return u.actions(a -> a.add(add -> add.index(index).alias(alias)));
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Info for what {@code aliasOrIndex} points at; a physical index name resolves to itself. */
    public Optional<AliasInfo> aliasInfo(String aliasOrIndex) {
        List<String> targets = aliasTargets(aliasOrIndex);
        if (targets.isEmpty() && exists(aliasOrIndex)) {
            targets = List.of(aliasOrIndex);
        }
        if (targets.isEmpty()) {
            return Optional.empty();
        }
        String index = targets.getFirst();
        try {
            var mapping = client.indices().getMapping(g -> g.index(index)).get(index).mappings();
            Map<String, String> meta = new LinkedHashMap<>();
            mapping.meta().forEach((k, v) -> meta.put(k, v.to(String.class)));
            return Optional.of(new AliasInfo(index, meta));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<String> aliasTargets(String alias) {
        try {
            return List.copyOf(client.indices().getAlias(g -> g.name(alias)).aliases().keySet());
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                return List.of();
            }
            throw e;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Object> mapping(int dims, Map<String, String> meta) {
        Map<String, Object> kw = Map.of("type", "keyword");
        Map<String, Object> integer = Map.of("type", "integer");
        Map<String, Object> props = new LinkedHashMap<>();
        for (String f : List.of("chunkId", "sectionId", "docId", "docVersion", "sourceType", "sectionPath",
                "coveredSections", "classification", "corpusVersion", "contentHash", "embeddingModel", "chunkerVersion")) {
            props.put(f, kw);
        }
        props.put("docTitle", Map.of("type", "text", "fields", Map.of("keyword", kw)));
        props.put("pages", integer);
        props.put("ordinal", integer);
        props.put("tokenCount", integer);
        props.put("content", Map.of("type", "text", "analyzer", "english"));
        props.put("ingestedAt", Map.of("type", "date"));
        // index_options is explicit: newer ES versions change the default quantisation (ROADMAP §4.2).
        props.put("embedding", Map.of("type", "dense_vector", "dims", dims, "similarity", "cosine", "index", true,
                "index_options", Map.of("type", "hnsw", "m", HNSW_M, "ef_construction", HNSW_EF_CONSTRUCTION)));
        return Map.of("dynamic", "strict", "_meta", meta, "properties", props);
    }

}
