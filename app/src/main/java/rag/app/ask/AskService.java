package rag.app.ask;

import com.github.f4b6a3.ulid.UlidCreator;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import rag.app.search.SearchHit;
import rag.app.search.HybridSearch;
import rag.app.search.Mode;
import rag.app.security.Principal;

/**
 * Phase 1 grounded answer: retrieval (any mode) top-k → prompt v1 → LLM. Every retrieved source is returned as a
 * numbered citation; validation and refusal arrive in Phase 3.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AskService {

    static final String NO_SOURCES = "No sources were found for this question.";
    static final int QUOTE_CHARS = 300;

    private final HybridSearch retriever;
    private final PromptRenderer renderer;
    private final ChatModel chat;

    public AskService(HybridSearch retriever, PromptRenderer renderer, ChatModel chat) {
        this.retriever = retriever;
        this.renderer = renderer;
        this.chat = chat;
    }

    public AskResponse ask(String question, int topK, Mode mode, Principal principal) {
        String answerId = "a_" + UlidCreator.getMonotonicUlid();
        long t0 = System.nanoTime();
        List<SearchHit> hits = retriever.search(question, topK, mode, principal);
        long retrievalMs = ms(t0);
        if (hits.isEmpty()) {
            return new AskResponse(answerId, "ANSWERED", NO_SOURCES, List.of(), new AskResponse.Usage(null, null),
                    new AskResponse.Latency(retrievalMs, 0, 0));
        }

        long t1 = System.nanoTime();
        ChatResponse response;
        try {
            response = chat.call(renderer.render(question, hits));
        } catch (RuntimeException e) {
            throw new GenerationUnavailableException("generation failed: " + e.getMessage(), e);
        }
        long generationMs = ms(t1);

        List<AskResponse.Citation> citations = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            SearchHit h = hits.get(i);
            String quote = h.text().length() <= QUOTE_CHARS ? h.text() : h.text().substring(0, QUOTE_CHARS);
            citations.add(new AskResponse.Citation(i + 1, h.chunkId(), h.docId(), h.sectionPath(), h.pages(), quote));
        }
        var usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        return new AskResponse(answerId, "ANSWERED", response.getResult().getOutput().getText(), citations,
                new AskResponse.Usage(usage == null ? null : usage.getPromptTokens(), usage == null ? null : usage.getCompletionTokens()),
                new AskResponse.Latency(retrievalMs, 0, generationMs));
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
