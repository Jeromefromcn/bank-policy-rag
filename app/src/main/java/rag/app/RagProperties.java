package rag.app;

import java.time.Duration;
import java.util.List;
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

    public record Embedding(String model, int numThread, int batchSize, String cacheDir, Duration timeout) {}

    public record Retrieval(int numCandidates) {}

    public record Chunking(int maxTokens, int overlapTokens) {}

    public record Corpus(String root) {}
}
