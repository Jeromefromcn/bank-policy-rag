package rag.app.embed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.web.client.ResourceAccessException;
import rag.app.RagProperties;

class OllamaEmbedderTest {

    private final EmbeddingModel model = mock(EmbeddingModel.class);
    private final OllamaEmbedder embedder =
            new OllamaEmbedder(model, new RagProperties.Embedding("nomic-embed-text", 3, 16, ".cache/embeddings"));

    @BeforeEach
    void answerWithVectors() {
        when(model.call(any())).thenAnswer(inv -> response(((EmbeddingRequest) inv.getArgument(0)).getInstructions().size(), 768));
    }

    static EmbeddingResponse response(int n, int dims) {
        List<Embedding> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new Embedding(new float[dims], i));
        }
        return new EmbeddingResponse(out);
    }

    private EmbeddingRequest captureOne() {
        var captor = ArgumentCaptor.forClass(EmbeddingRequest.class);
        verify(model).call(captor.capture());
        return captor.getValue();
    }

    @Test
    void addsQueryPrefix() {
        embedder.embedQuery("what is X");
        assertThat(captureOne().getInstructions()).containsExactly("search_query: what is X");
    }

    @Test
    void addsDocumentPrefix() {
        embedder.embedDocuments(List.of("alpha"));
        assertThat(captureOne().getInstructions()).containsExactly("search_document: alpha");
    }

    @Test
    void batchesDocumentsAndKeepsOrder() {
        var texts = IntStream.range(0, 35).mapToObj(i -> "t" + i).toList();
        assertThat(embedder.embedDocuments(texts)).hasSize(35);
        verify(model, times(3)).call(any());
    }

    @Test
    void passesModelAndNumThread() {
        embedder.embedQuery("x");
        var options = (OllamaEmbeddingOptions) captureOne().getOptions();
        assertThat(options.getModel()).isEqualTo("nomic-embed-text");
        assertThat(options.getNumThread()).isEqualTo(3);
    }

    @Test
    void identity() {
        assertThat(embedder.modelId()).isEqualTo("ollama/nomic-embed-text");
        assertThat(embedder.shortName()).isEqualTo("nomic");
        assertThat(embedder.dims()).isEqualTo(768);
    }

    @Test
    void wrongDimsFails() {
        doReturn(response(1, 384)).when(model).call(any(EmbeddingRequest.class));
        assertThatThrownBy(() -> embedder.embedQuery("x")).isInstanceOf(IllegalStateException.class).hasMessageContaining("384");
    }

    @Test
    void transportErrorBecomesUnavailable() {
        doThrow(new ResourceAccessException("down")).when(model).call(any(EmbeddingRequest.class));
        assertThatThrownBy(() -> embedder.embedQuery("x")).isInstanceOf(EmbeddingUnavailableException.class);
    }
}
