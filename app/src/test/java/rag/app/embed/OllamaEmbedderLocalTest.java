package rag.app.embed;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import rag.app.RagProperties;

/** Runs against the real local Ollama: {@code ./gradlew :app:localTest}. */
@Tag("local")
class OllamaEmbedderLocalTest {

    @Test
    void realOllamaReturns768() {
        String url = System.getenv().getOrDefault("OLLAMA_URL", "http://127.0.0.1:11434");
        var model = OllamaEmbeddingModel.builder().ollamaApi(OllamaApi.builder().baseUrl(url).build()).build();
        var embedder = new OllamaEmbedder(model, OllamaEmbedderTest.config("nomic"));
        assertThat(embedder.embedQuery("tolerance for disruption")).hasSize(768);
        assertThat(embedder.embedDocuments(java.util.List.of("a", "b"))).hasSize(2);
    }
}
