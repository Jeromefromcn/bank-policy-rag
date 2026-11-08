package rag.app.embed;

import java.nio.file.Path;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import rag.app.RagProperties;

@Configuration
public class EmbeddingConfig {

    @Bean
    @ConditionalOnProperty(name = "spring.ai.model.embedding", havingValue = "ollama")
    Embedder ollamaEmbedder(EmbeddingModel model, RagProperties props) {
        return new OllamaEmbedder(model, props.embedding());
    }

    @Bean
    EmbeddingCache embeddingCache(RagProperties props) {
        return new EmbeddingCache(Path.of(props.embedding().cacheDir()));
    }
}
