package rag.app.embed;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import rag.app.RagProperties;

/** An Ollama embedding model via Spring AI's {@link EmbeddingModel}, with the task prefixes of its configured {@code ModelSpec}. */
public class OllamaEmbedder implements Embedder {

    private final EmbeddingModel model;
    private final RagProperties.Embedding cfg;
    private final RagProperties.ModelSpec spec;
    private final OllamaEmbeddingOptions options;

    public OllamaEmbedder(EmbeddingModel model, RagProperties.Embedding cfg) {
        this.spec = cfg.models().get(cfg.model());
        if (spec == null) {
            throw new IllegalArgumentException("unknown embedding model '" + cfg.model() + "'; configured: "
                    + new java.util.TreeSet<>(cfg.models().keySet()));
        }
        this.model = model;
        this.cfg = cfg;
        this.options = OllamaEmbeddingOptions.builder().model(spec.ollamaModel()).numThread(cfg.numThread()).build();
    }

    @Override public String modelId() { return "ollama/" + spec.ollamaModel(); }
    @Override public String shortName() { return spec.shortName(); }
    @Override public int dims() { return spec.dims(); }

    @Override
    public float[] embedQuery(String query) {
        return call(List.of(EmbeddingPrefixes.query(spec, query))).getFirst();
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += cfg.batchSize()) {
            List<String> batch = texts.subList(i, Math.min(texts.size(), i + cfg.batchSize()))
                    .stream().map(t -> EmbeddingPrefixes.document(spec, t)).toList();
            out.addAll(call(batch));
        }
        return out;
    }

    private List<float[]> call(List<String> inputs) {
        EmbeddingResponse response;
        try {
            response = model.call(new EmbeddingRequest(inputs, options));
        } catch (RuntimeException e) {
            throw new EmbeddingUnavailableException("embedding call to " + modelId() + " failed: " + e.getMessage(), e);
        }
        List<Embedding> results = response.getResults();
        if (results.size() != inputs.size()) {
            throw new IllegalStateException("expected " + inputs.size() + " embeddings, got " + results.size());
        }
        List<float[]> vectors = new ArrayList<>(results.size());
        for (Embedding e : results) {
            float[] v = e.getOutput();
            if (v.length != dims()) {
                throw new IllegalStateException(modelId() + " returned " + v.length + " dims, expected " + dims());
            }
            vectors.add(v);
        }
        return vectors;
    }
}
