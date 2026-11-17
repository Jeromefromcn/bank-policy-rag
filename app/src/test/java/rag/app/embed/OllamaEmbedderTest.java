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
import java.util.Map;
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
    static final RagProperties.ModelSpec NOMIC =
            new RagProperties.ModelSpec("nomic-embed-text", "nomic", 768, "search_query: ", "search_document: ");
    static final RagProperties.ModelSpec BGE_SMALL = new RagProperties.ModelSpec("hf.co/CompendiumLabs/bge-small-en-v1.5-gguf:Q8_0",
            "bgesmall", 384, "Represent this sentence for searching relevant passages: ", "");
    static final RagProperties.ModelSpec BGE_M3 = new RagProperties.ModelSpec("bge-m3", "bgem3", 1024, "", "");

    static RagProperties.Embedding config(String selected) {
        return new RagProperties.Embedding(selected, 3, 16, ".cache/embeddings",
                Map.of("nomic", NOMIC, "bge-small", BGE_SMALL, "bge-m3", BGE_M3), "");
    }

    private final OllamaEmbedder embedder = new OllamaEmbedder(model, config("nomic"));

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

    @Test
    void bgeSmallPrefixesQueriesOnly() {
        var bge = new OllamaEmbedder(model, config("bge-small"));
        doReturn(response(1, 384)).when(model).call(any(EmbeddingRequest.class));

        bge.embedQuery("what is X");
        bge.embedDocuments(List.of("alpha"));

        var captor = ArgumentCaptor.forClass(EmbeddingRequest.class);
        verify(model, times(2)).call(captor.capture());
        assertThat(captor.getAllValues().get(0).getInstructions())
                .containsExactly("Represent this sentence for searching relevant passages: what is X");
        assertThat(captor.getAllValues().get(1).getInstructions()).containsExactly("alpha");
        assertThat(bge.dims()).isEqualTo(384);
        assertThat(bge.modelId()).isEqualTo("ollama/hf.co/CompendiumLabs/bge-small-en-v1.5-gguf:Q8_0");
        assertThat(bge.shortName()).isEqualTo("bgesmall");
    }

    @Test
    void bgeM3UsesNoPrefixAtAll() {
        var m3 = new OllamaEmbedder(model, config("bge-m3"));
        doReturn(response(1, 1024)).when(model).call(any(EmbeddingRequest.class));

        m3.embedQuery("what is X");

        assertThat(captureOne().getInstructions()).containsExactly("what is X");
        assertThat(m3.dims()).isEqualTo(1024);
    }

    @Test
    void wrongDimsMessageNamesTheModel() {
        var m3 = new OllamaEmbedder(model, config("bge-m3"));
        doReturn(response(1, 768)).when(model).call(any(EmbeddingRequest.class));
        assertThatThrownBy(() -> m3.embedQuery("x")).hasMessageContaining("ollama/bge-m3").hasMessageContaining("768");
    }

    @Test
    void unknownModelNameListsTheConfiguredOnes() {
        assertThatThrownBy(() -> new OllamaEmbedder(model, config("nope")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nope").hasMessageContaining("bge-small");
    }
}
