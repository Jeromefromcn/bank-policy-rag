package rag.app.embed;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import rag.app.RagProperties;
import rag.app.fixture.FixtureFiles;

/**
 * Vector lookup for CI (spec 05 §7.1): the same {@link Embedder} interface as Ollama, but vectors come from
 * committed fixture files keyed by {@code sha256(prefix + text)}. A miss is a loud failure, never a zero vector.
 */
public class FixtureEmbedder implements Embedder {

    private final RagProperties.ModelSpec spec;
    private final String modelId;
    private final Map<String, float[]> vectors;

    public FixtureEmbedder(Path dir, RagProperties.Embedding cfg) {
        this.spec = cfg.models().get(cfg.model());
        if (spec == null) {
            throw new IllegalArgumentException("unknown embedding model '" + cfg.model() + "'");
        }
        Map<String, float[]> all = new LinkedHashMap<>(FixtureFiles.readVectors(dir, "chunks.jsonl"));
        all.putAll(FixtureFiles.readVectors(dir, "questions.jsonl"));
        this.vectors = Map.copyOf(all);
        this.modelId = "ollama/" + spec.ollamaModel();
    }

    @Override public String modelId() { return modelId; }
    @Override public String shortName() { return spec.shortName(); }
    @Override public int dims() { return spec.dims(); }

    @Override
    public float[] embedQuery(String query) {
        return lookup(EmbeddingPrefixes.query(spec, query));
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        return texts.stream().map(t -> lookup(EmbeddingPrefixes.document(spec, t))).toList();
    }

    private float[] lookup(String prefixed) {
        String hash = FixtureFiles.textHash(prefixed);
        float[] vector = vectors.get(hash);
        if (vector == null) {
            throw new IllegalStateException("no fixture vector for " + hash + " (" + modelId + ")");
        }
        return vector;
    }
}
