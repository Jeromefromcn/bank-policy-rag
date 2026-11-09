package rag.app.ask;

import com.github.f4b6a3.ulid.UlidCreator;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;
import rag.app.RagProperties;
import rag.app.search.HybridSearch;
import rag.app.search.Mode;
import rag.app.search.SearchHit;
import rag.app.security.Principal;
import rag.core.cite.CitationReport;
import rag.core.cite.CitationValidator;

/**
 * Grounded answer (spec 03): retrieve, refuse below the similarity gate without calling the model, render
 * {@code answer.v2}, treat {@code NO_ANSWER} as a refusal, validate citations, return only cited sources.
 * The external refusal reason never reveals whether sources were inaccessible or absent (spec 00 §7 rule 2);
 * the true internal cause is logged.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AskService {

    static final String LOW_RETRIEVAL_SCORE = "LOW_RETRIEVAL_SCORE";
    static final String CITATION_VALIDATION_FAILED = "CITATION_VALIDATION_FAILED";
    static final int QUOTE_CHARS = 300;

    private static final Logger LOG = LoggerFactory.getLogger(AskService.class);

    private final HybridSearch retriever;
    private final PromptRenderer renderer;
    private final ChatModel chat;
    private final double minSimilarity;
    private final CitationValidator validator;

    public AskService(HybridSearch retriever, PromptRenderer renderer, ChatModel chat, RagProperties props) {
        this.retriever = retriever;
        this.renderer = renderer;
        this.chat = chat;
        this.minSimilarity = props.ask().minSimilarity();
        this.validator = new CitationValidator(props.ask().minOverlap());
    }

    public AskResponse ask(String question, int topK, Mode mode, Principal principal) {
        String answerId = "a_" + UlidCreator.getMonotonicUlid();
        long t0 = System.nanoTime();
        var outcome = retriever.searchWithDiagnostics(question, topK, mode, principal);
        long retrievalMs = ms(t0);
        List<SearchHit> hits = outcome.hits();

        if (hits.isEmpty()) {
            return refused(answerId, LOW_RETRIEVAL_SCORE, "no accessible hits", null, retrievalMs, 0);
        }
        Double best = outcome.bestVectorSimilarity();
        if (minSimilarity > 0 && best != null && best < minSimilarity) {
            return refused(answerId, LOW_RETRIEVAL_SCORE, "similarity " + best + " below gate " + minSimilarity, null,
                    retrievalMs, 0);
        }

        long t1 = System.nanoTime();
        ChatResponse response;
        try {
            response = chat.call(renderer.render(question, hits));
        } catch (RuntimeException e) {
            throw new GenerationUnavailableException("generation failed: " + e.getMessage(), e);
        }
        long generationMs = ms(t1);
        var usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        var responseUsage = new AskResponse.Usage(usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens());
        String answer = response.getResult().getOutput().getText();
        answer = answer == null ? "" : answer.strip();

        if (answer.isEmpty() || PromptRenderer.isNoAnswer(answer)) {
            return refused(answerId, LOW_RETRIEVAL_SCORE, "model declined (NO_ANSWER or empty)", responseUsage, retrievalMs,
                    generationMs);
        }
        CitationReport report = validator.validate(answer, hits.stream().map(SearchHit::text).toList());
        if (report.hardFail()) {
            return refused(answerId, CITATION_VALIDATION_FAILED, "range=" + report.rangeOk() + " coverage=" + report.coverageOk(),
                    responseUsage, retrievalMs, generationMs);
        }
        long flagged = report.sentences().stream().filter(CitationReport.SentenceCheck::flagged).count();
        if (flagged > 0) {
            LOG.info("answer {} has {} low-overlap cited sentence(s)", answerId, flagged);
        }

        List<AskResponse.Citation> citations = new ArrayList<>();
        for (int ref : new TreeSet<>(report.citedRefs())) {
            SearchHit h = hits.get(ref - 1);
            String quote = h.text().length() <= QUOTE_CHARS ? h.text() : h.text().substring(0, QUOTE_CHARS);
            citations.add(new AskResponse.Citation(ref, h.chunkId(), h.docId(), h.sectionPath(), h.pages(), quote));
        }
        return new AskResponse(answerId, "ANSWERED", null, answer, citations, responseUsage,
                new AskResponse.Latency(retrievalMs, 0, generationMs));
    }

    private static AskResponse refused(String answerId, String reason, String internalCause, AskResponse.Usage usage,
            long retrievalMs, long generationMs) {
        LOG.info("answer {} refused ({}): {}", answerId, reason, internalCause);
        return new AskResponse(answerId, "REFUSED", reason, null, List.of(), usage == null ? new AskResponse.Usage(null, null) : usage,
                new AskResponse.Latency(retrievalMs, 0, generationMs));
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
