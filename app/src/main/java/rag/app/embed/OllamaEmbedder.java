package rag.app.embed;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import rag.app.RagProperties;

/** {@code nomic-embed-text} via Spring AI's Ollama {@link EmbeddingModel}, with its task prefixes. */
public class OllamaEmbedder implements Embedder {

    static final String QUERY_PREFIX = "search_query: ";
    static final String DOCUMENT_PREFIX = "search_document: ";
    static final int DIMS = 768;

    private final EmbeddingModel model;
    private final RagProperties.Embedding cfg;
    private final OllamaEmbeddingOptions options;

    public OllamaEmbedder(EmbeddingModel model, RagProperties.Embedding cfg) {
        if (!cfg.model().equals("nomic-embed-text")) {
            // Prefixes and dimensions below are specific to nomic; other models arrive in Phase 2.
            throw new IllegalArgumentException("unsupported embedding model: " + cfg.model());
        }
        this.model = model;
        this.cfg = cfg;
        this.options = OllamaEmbeddingOptions.builder().model(cfg.model()).numThread(cfg.numThread()).build();
    }

    @Override public String modelId() { return "ollama/" + cfg.model(); }
    @Override public String shortName() { return "nomic"; }
    @Override public int dims() { return DIMS; }

    @Override
    public float[] embedQuery(String query) {
        return call(List.of(QUERY_PREFIX + query)).getFirst();
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += cfg.batchSize()) {
            List<String> batch = texts.subList(i, Math.min(texts.size(), i + cfg.batchSize()))
                    .stream().map(t -> DOCUMENT_PREFIX + t).toList();
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
            if (v.length != DIMS) {
                throw new IllegalStateException(modelId() + " returned " + v.length + " dims, expected " + DIMS);
            }
            vectors.add(v);
        }
        return vectors;
    }
}
