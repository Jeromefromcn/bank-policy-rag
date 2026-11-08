package rag.app;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import rag.core.model.Clearance;

/** Every limit, threshold and model id is a property (spec 00 §5). Defaults live in application.yaml. */
@ConfigurationProperties("rag")
public record RagProperties(
        String envPrefix, Security security, Embedding embedding, Retrieval retrieval, Chunking chunking, Corpus corpus) {

    public record Security(List<ApiKey> apiKeys) {
        public Security {
            apiKeys = apiKeys == null ? List.of() : List.copyOf(apiKeys);
        }
    }

    public record ApiKey(String key, String principal, Clearance clearance, Set<String> roles) {
        public ApiKey {
            roles = roles == null ? Set.of() : Set.copyOf(roles);
        }
    }

    /**
     * @param model key into {@code models}; selects the embedding model for this process
     */
    public record Embedding(String model, int numThread, int batchSize, String cacheDir, Map<String, ModelSpec> models) {
        public Embedding {
            models = models == null ? Map.of() : Map.copyOf(models);
        }
    }

    /** One embedding model: its Ollama tag, the short name used in index names, dims and task prefixes (may be empty). */
    public record ModelSpec(String ollamaModel, String shortName, int dims, String queryPrefix, String documentPrefix) {
        public ModelSpec {
            queryPrefix = queryPrefix == null ? "" : queryPrefix;
            documentPrefix = documentPrefix == null ? "" : documentPrefix;
        }
    }

    /**
     * @param chunksIndex   index or alias that searches read; blank means the {@code <prefix>-chunks} alias
     * @param sectionsIndex likewise for {@code <prefix>-sections}
     * @param legSize       hits requested from each leg of a hybrid search
     */
    public record Retrieval(int numCandidates, int legSize, String chunksIndex, String sectionsIndex) {

        /** The index or alias searches read: the configured one, else the {@code <prefix>-chunks} alias. */
        public String chunksTarget(String envPrefix) {
            return chunksIndex == null || chunksIndex.isBlank() ? rag.app.es.IndexNames.chunksAlias(envPrefix) : chunksIndex;
        }

        public String sectionsTarget(String envPrefix) {
            return sectionsIndex == null || sectionsIndex.isBlank() ? rag.app.es.IndexNames.sectionsAlias(envPrefix) : sectionsIndex;
        }
    }

    /** @param kind {@code fixed} or {@code structural}: the chunker ingest uses unless {@code --chunker} says otherwise */
    public record Chunking(String kind, int maxTokens, int overlapTokens, Structural structural) {}

    public record Structural(int maxTokens, int minTokens) {}

    public record Corpus(String root) {}
}
