package rag.app.ask;

import com.github.f4b6a3.ulid.UlidCreator;
import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import rag.app.RagProperties;
import rag.app.audit.AuditRecord;
import rag.app.audit.AuditService;
import rag.app.audit.InternalRefusalCause;
import rag.app.audit.VisibilityProbe;
import rag.app.conversation.ConversationStore;
import rag.app.conversation.QueryRewriter;
import rag.app.conversation.Turn;
import rag.app.es.ChunkIndexAdmin;
import rag.app.metrics.Observations;
import rag.app.metrics.RequestMetrics;
import rag.app.search.Mode;
import rag.app.search.RetrievalPipeline;
import rag.app.search.SearchHit;
import rag.app.security.Principal;
import rag.app.web.ApiException;
import rag.core.cite.CitationReport;
import rag.core.cite.CitationValidator;

/**
 * Grounded answer (spec 03, spec 04 §4-§6): resolve the conversation and rewrite a follow-up, retrieve (optionally reranked), refuse below the similarity gate or the
 * rerank gate without calling the model, render {@code answer.v2}, treat {@code NO_ANSWER} as a refusal, validate citations, return only cited sources.
 * The external refusal reason never reveals whether sources were inaccessible or absent (spec 00 §7 rule 2);
 * the true internal cause is written to the audit record only. A turn is stored only when the request,
 * including its audit write, completes.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AskService {

    static final String LOW_RETRIEVAL_SCORE = "LOW_RETRIEVAL_SCORE";
    static final String CITATION_VALIDATION_FAILED = "CITATION_VALIDATION_FAILED";
    static final int QUOTE_CHARS = 300;

    private static final Logger LOG = LoggerFactory.getLogger(AskService.class);

    private final RetrievalPipeline retriever;
    private final PromptRenderer renderer;
    private final ChatModel chat;
    private final double minSimilarity;
    private final double minRerankProbability;
    private final CitationValidator validator;
    private final ConversationStore conversations;
    private final QueryRewriter rewriter;
    private final RequestMetrics metrics;
    private final ObservationRegistry observations;
    private final Environment env;
    private final AuditService audit;
    private final VisibilityProbe probe;
    private final ChunkIndexAdmin indexAdmin;
    private final RagProperties props;

    public AskService(RetrievalPipeline retriever, PromptRenderer renderer, ChatModel chat, RagProperties props,
            ConversationStore conversations, QueryRewriter rewriter, RequestMetrics metrics,
            ObservationRegistry observations, Environment env, AuditService audit, VisibilityProbe probe,
            ChunkIndexAdmin indexAdmin) {
        this.retriever = retriever;
        this.conversations = conversations;
        this.rewriter = rewriter;
        this.renderer = renderer;
        this.chat = chat;
        this.metrics = metrics;
        this.observations = observations;
        this.env = env;
        this.audit = audit;
        this.probe = probe;
        this.indexAdmin = indexAdmin;
        this.props = props;
        this.minSimilarity = props.ask().minSimilarity();
        this.minRerankProbability = props.ask().minRerankProbability();
        this.validator = new CitationValidator(props.ask().minOverlap());
    }

    public AskResponse ask(String question, int topK, Mode mode, boolean rerank, String conversationId,
            Principal principal) {
        List<Turn> history = conversationId == null ? List.of()
                : conversations.history(conversationId, principal).orElseThrow(ApiException::notFound);
        String answerId = "a_" + UlidCreator.getMonotonicUlid();

        QueryRewriter.Rewrite rewrite = history.isEmpty() ? null : rewriter.rewrite(history, question);
        String query = rewrite == null ? question : rewrite.query();
        if (rewrite != null) {
            LOG.info("answer {} rewritten query{}: {}", answerId, rewrite.fellBack() ? " (fell back to original)" : "", query);
        }

        AnswerOutcome outcome = answer(answerId, query, topK, mode, rerank, principal);
        AskResponse r = outcome.response();
        var usage = rewrite == null ? r.usage() : r.usage().plus(rewrite.promptTokens(), rewrite.completionTokens());
        long rewriteMs = rewrite == null ? 0 : rewrite.latencyMs();
        AuditRecord record = askRecord(outcome, question, rewrite == null ? null : query, principal, topK, mode, rerank,
                usage, rewriteMs);

        long writeMs;
        try {
            writeMs = audit.write(record);
        } catch (RuntimeException e) {
            metrics.request("ask", "error");
            throw e;
        }
        metrics.request("ask", r.status().equals("ANSWERED") ? "answered" : "refused");
        if (r.status().equals("REFUSED")) {
            metrics.refusal(r.refusalReason());
        }

        var lat = r.latencyMs();
        AskResponse audited = new AskResponse(r.answerId(), r.status(), r.refusalReason(), r.answer(), r.citations(), usage,
                new AskResponse.Latency(lat.retrieval(), lat.rerank(), lat.generation(), rewriteMs,
                        outcome.probeMs() + writeMs), null, null);

        String id = conversationId == null ? conversations.create(principal) : conversationId;
        conversations.append(id, principal, new Turn(question, rewrite == null ? null : query, r.status(), r.answer()));
        return new AskResponse(audited.answerId(), audited.status(), audited.refusalReason(), audited.answer(),
                audited.citations(), audited.usage(), audited.latencyMs(), id, rewrite == null ? null : query);
    }

    private AnswerOutcome answer(String answerId, String question, int topK, Mode mode, boolean rerank,
            Principal principal) {
        var outcome = retriever.retrieve(question, topK, mode, rerank, principal);
        long retrievalMs = outcome.retrievalMs();
        long rerankMs = outcome.rerankMs();
        List<SearchHit> hits = outcome.hits();
        metrics.stage("retrieval", retrievalMs);
        metrics.stage("rerank", rerankMs);
        Double best = outcome.bestVectorSimilarity();
        Double bestRerank = outcome.bestRerankProbability();

        if (hits.isEmpty()) {
            long p0 = System.nanoTime();
            InternalRefusalCause cause = probe.cause(question, principal);
            long probeMs = ms(p0);
            metrics.emptyRetrieval("ask", cause);
            return new AnswerOutcome(refused(answerId, LOW_RETRIEVAL_SCORE, "no accessible hits", null, retrievalMs,
                    rerankMs, 0, probeMs), List.of(), null, best, bestRerank, cause, probeMs);
        }
        if (minSimilarity > 0 && best != null && best < minSimilarity) {
            return new AnswerOutcome(refused(answerId, LOW_RETRIEVAL_SCORE, "similarity " + best + " below gate "
                    + minSimilarity, null, retrievalMs, rerankMs, 0, 0), hits, null, best, bestRerank, null, 0);
        }
        if (rerank && minRerankProbability > 0 && bestRerank != null && bestRerank < minRerankProbability) {
            return new AnswerOutcome(refused(answerId, LOW_RETRIEVAL_SCORE, "rerank probability " + bestRerank
                    + " below gate " + minRerankProbability, null, retrievalMs, rerankMs, 0, 0), hits, null, best,
                    bestRerank, null, 0);
        }

        long t1 = System.nanoTime();
        String model = env.getProperty("spring.ai.deepseek.chat.options.model", "");
        PromptRenderer.Rendered rendered = renderer.renderDetailed(question, hits);
        ChatResponse response;
        try {
            response = Observations.observe(observations, "rag.generation", Map.of("model", model),
                    () -> chat.call(rendered.toPrompt()));
        } catch (RuntimeException e) {
            throw new GenerationUnavailableException("generation failed: " + e.getMessage(), e);
        }
        long generationMs = ms(t1);
        metrics.stage("generation", generationMs);
        var usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        var responseUsage = new AskResponse.Usage(usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens());
        metrics.tokens(model, responseUsage.promptTokens(), responseUsage.completionTokens());
        String answer = response.getResult().getOutput().getText();
        answer = answer == null ? "" : answer.strip();

        if (answer.isEmpty() || PromptRenderer.isNoAnswer(answer)) {
            return new AnswerOutcome(refused(answerId, LOW_RETRIEVAL_SCORE, "model declined (NO_ANSWER or empty)",
                    responseUsage, retrievalMs, rerankMs, generationMs, 0), hits, rendered, best, bestRerank, null, 0);
        }
        CitationReport report = validator.validate(answer, hits.stream().map(SearchHit::text).toList());
        if (report.hardFail()) {
            return new AnswerOutcome(refused(answerId, CITATION_VALIDATION_FAILED, "range=" + report.rangeOk()
                    + " coverage=" + report.coverageOk(), responseUsage, retrievalMs, rerankMs, generationMs, 0), hits,
                    rendered, best, bestRerank, null, 0);
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
        return new AnswerOutcome(new AskResponse(answerId, "ANSWERED", null, answer, citations, responseUsage,
                new AskResponse.Latency(retrievalMs, rerankMs, generationMs, 0, 0), null, null), hits, rendered, best,
                bestRerank, null, 0);
    }

    private AuditRecord askRecord(AnswerOutcome o, String question, String rewritten, Principal principal, int topK,
            Mode mode, boolean rerank, AskResponse.Usage usage, long rewriteMs) {
        AskResponse r = o.response();
        Map<String, String> meta = indexAdmin.aliasInfo(props.retrieval().chunksTarget(props.envPrefix()))
                .map(ChunkIndexAdmin.AliasInfo::meta).orElse(Map.of());
        List<AuditRecord.Hit> hits = new ArrayList<>();
        for (int i = 0; i < o.hits().size(); i++) {
            SearchHit h = o.hits().get(i);
            hits.add(new AuditRecord.Hit(h.chunkId(), h.contentHash(), i + 1, h.score(), h.docId(), h.sectionPath(),
                    h.classification()));
        }
        List<AuditRecord.Citation> citations = r.citations().stream()
                .map(c -> new AuditRecord.Citation(c.ref(), c.chunkId(), c.docId(), c.sectionPath(), c.pages(), c.quote()))
                .toList();
        String model = env.getProperty("spring.ai.deepseek.chat.options.model", "");
        String provider = env.getProperty("spring.ai.model.chat", "deepseek");
        Double temperature = env.getProperty("spring.ai.deepseek.chat.options.temperature", Double.class);
        Integer maxTokens = env.getProperty("spring.ai.deepseek.chat.options.max-tokens", Integer.class);
        PromptRenderer.Rendered rendered = o.prompt();
        return new AuditRecord(r.answerId(), null, Instant.now().toString(), audit.traceId(), principal.name(),
                principal.clearance().wire(), "ask", question, rewritten,
                new AuditRecord.Retrieval(mode.name(), topK, rerank, o.bestVector(), o.bestRerank(), hits),
                rendered == null ? null : renderer.templateVersion(),
                rendered == null ? null : rendered.text(),
                rendered == null ? null : new AuditRecord.Model(provider, model, temperature, maxTokens),
                r.answer(), citations, r.status(), r.refusalReason(),
                o.cause() == null ? null : o.cause().name(),
                new AuditRecord.Usage(usage.promptTokens(), usage.completionTokens()),
                new AuditRecord.Latency(r.latencyMs().retrieval(), r.latencyMs().rerank(), r.latencyMs().generation(),
                        rewriteMs, o.probeMs()),
                meta.get("corpusVersion"), meta.get("chunkerVersion"), meta.get("embeddingModel"));
    }

    private static AskResponse refused(String answerId, String reason, String internalCause, AskResponse.Usage usage,
            long retrievalMs, long rerankMs, long generationMs, long auditMs) {
        LOG.info("answer {} refused ({}): {}", answerId, reason, internalCause);
        return new AskResponse(answerId, "REFUSED", reason, null, List.of(),
                usage == null ? new AskResponse.Usage(null, null) : usage,
                new AskResponse.Latency(retrievalMs, rerankMs, generationMs, 0, auditMs), null, null);
    }

    private record AnswerOutcome(AskResponse response, List<SearchHit> hits, PromptRenderer.Rendered prompt,
            Double bestVector, Double bestRerank, InternalRefusalCause cause, long probeMs) {}

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
