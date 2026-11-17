package rag.app.audit;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;
import rag.app.RagProperties;
import rag.app.ask.GenerationUnavailableException;
import rag.app.ask.PromptRenderer;
import rag.app.es.ChunkDocument;
import rag.app.web.ApiException;
import rag.app.web.ProblemTypes;
import rag.core.cite.CitationReport;
import rag.core.cite.CitationValidator;

/**
 * Replays an audit record (spec 05 §4.4): verifies every recorded hit's {@code contentHash}, then either
 * regenerates from the recorded prompt or reproduces a pre-generation refusal without a model call.
 * Replay never retrieves again and never writes a second audit record.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
public class ReplayService {

    static final int QUOTE_CHARS = 300;
    private static final Logger LOG = LoggerFactory.getLogger(ReplayService.class);

    public record ReplayOutcome(String status, String answer, List<AuditRecord.Citation> citations,
            String refusalReason, AuditRecord.Usage usage) {}

    public record ReplayResult(AuditRecord original, ReplayOutcome replay, boolean modelCall, String recordedModel,
            String currentModel, boolean modelMatches, ContentHashes contentHashes, long generationMs) {}

    public record ContentHashes(int checked, int matched) {}

    private final ElasticsearchClient client;
    private final RagProperties props;
    private final ChatModel chat;
    private final Environment env;
    private final CitationValidator validator;

    public ReplayService(ElasticsearchClient client, RagProperties props, ChatModel chat, Environment env) {
        this.client = client;
        this.props = props;
        this.chat = chat;
        this.env = env;
        this.validator = new CitationValidator(props.ask().minOverlap());
    }

    public ReplayResult replay(AuditRecord record) {
        if (!"ask".equals(record.endpoint())) {
            throw new ApiException(HttpStatus.CONFLICT, ProblemTypes.REPLAY_UNSUPPORTED,
                    "only /v1/ask records can be replayed");
        }
        List<AuditRecord.Hit> recorded = record.retrieval() == null ? List.of() : record.retrieval().hits();
        Map<String, ChunkDocument> current = fetch(recorded);
        int checked = recorded.size();
        int matched = 0;
        for (AuditRecord.Hit hit : recorded) {
            ChunkDocument doc = current.get(hit.chunkId());
            if (doc != null && java.util.Objects.equals(doc.contentHash(), hit.contentHash())) {
                matched++;
            }
        }
        if (matched != checked) {
            throw new ApiException(HttpStatus.CONFLICT, ProblemTypes.CONTENT_HASH_MISMATCH,
                    (checked - matched) + " of " + checked + " recorded chunks no longer match");
        }

        String currentModel = env.getProperty("spring.ai.deepseek.chat.options.model", "");
        String recordedModel = record.model() == null ? null : record.model().id();
        boolean modelMatches = recordedModel != null && recordedModel.equals(currentModel);

        if (record.renderedPrompt() == null) {
            ReplayOutcome outcome = new ReplayOutcome(record.status(), record.answer(), record.citations(),
                    record.refusalReason(), record.usage());
            LOG.info("replay {} (no prompt): {}", record.id(), record.status());
            return new ReplayResult(record, outcome, false, recordedModel, currentModel, modelMatches, new ContentHashes(checked, matched), 0);
        }

        long t0 = System.nanoTime();
        ChatOptions options = null;
        if (record.model() != null) {
            var builder = ChatOptions.builder();
            if (record.model().temperature() != null) {
                builder.temperature(record.model().temperature());
            }
            if (record.model().maxTokens() != null) {
                builder.maxTokens(record.model().maxTokens());
            }
            options = builder.build();
        }
        ChatResponse response;
        try {
            response = chat.call(PromptRenderer.parse(record.renderedPrompt(), options));
        } catch (RuntimeException e) {
            throw new GenerationUnavailableException("replay generation failed: " + e.getMessage(), e);
        }
        long generationMs = (System.nanoTime() - t0) / 1_000_000;
        var usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        var replayUsage = new AuditRecord.Usage(usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens());
        String answer = response.getResult().getOutput().getText();
        answer = answer == null ? "" : answer.strip();

        if (answer.isEmpty() || PromptRenderer.isNoAnswer(answer)) {
            LOG.info("replay {}: model declined", record.id());
            return new ReplayResult(record, new ReplayOutcome("REFUSED", null, List.of(), "LOW_RETRIEVAL_SCORE",
                    replayUsage), true, recordedModel, currentModel, modelMatches, new ContentHashes(checked, matched), generationMs);
        }

        List<String> texts = recorded.stream().map(h -> current.get(h.chunkId()).content()).toList();
        CitationReport report = validator.validate(answer, texts);
        if (report.hardFail()) {
            LOG.info("replay {}: citation validation failed", record.id());
            return new ReplayResult(record, new ReplayOutcome("REFUSED", null, List.of(), "CITATION_VALIDATION_FAILED",
                    replayUsage), true, recordedModel, currentModel, modelMatches, new ContentHashes(checked, matched), generationMs);
        }

        List<AuditRecord.Citation> citations = new ArrayList<>();
        for (int ref : new TreeSet<>(report.citedRefs())) {
            AuditRecord.Hit hit = recorded.get(ref - 1);
            String text = texts.get(ref - 1);
            String quote = text.length() <= QUOTE_CHARS ? text : text.substring(0, QUOTE_CHARS);
            citations.add(new AuditRecord.Citation(ref, hit.chunkId(), hit.docId(), hit.sectionPath(), List.of(), quote));
        }
        LOG.info("replay {}: answered with {} citation(s)", record.id(), citations.size());
        return new ReplayResult(record, new ReplayOutcome("ANSWERED", answer, citations, null, replayUsage), true,
                recordedModel, currentModel, modelMatches, new ContentHashes(checked, matched), generationMs);
    }

    private Map<String, ChunkDocument> fetch(List<AuditRecord.Hit> recorded) {
        if (recorded.isEmpty()) {
            return Map.of();
        }
        List<String> ids = recorded.stream().map(AuditRecord.Hit::chunkId).toList();
        try {
            var hits = client.search(s -> s.index(props.retrieval().chunksTarget(props.envPrefix()))
                            .size(ids.size())
                            .query(q -> q.ids(i -> i.values(ids)))
                            .source(src -> src.filter(f -> f.excludes("embedding"))),
                    ChunkDocument.class).hits().hits();
            Map<String, ChunkDocument> out = new LinkedHashMap<>();
            for (var hit : hits) {
                out.put(hit.source().chunkId(), hit.source());
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
