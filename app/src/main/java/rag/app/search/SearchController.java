package rag.app.search;

import com.github.f4b6a3.ulid.UlidCreator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import rag.app.RagProperties;
import rag.app.audit.AuditRecord;
import rag.app.audit.AuditService;
import rag.app.audit.InternalRefusalCause;
import rag.app.audit.VisibilityProbe;
import rag.app.es.ChunkIndexAdmin;
import rag.app.metrics.RequestMetrics;
import rag.app.security.Principal;

@RestController
public class SearchController {

    private final RetrievalPipeline pipeline;
    private final RequestMetrics metrics;
    private final AuditService audit;
    private final VisibilityProbe probe;
    private final ChunkIndexAdmin indexAdmin;
    private final RagProperties props;

    public SearchController(RetrievalPipeline pipeline, RequestMetrics metrics, AuditService audit,
            VisibilityProbe probe, ChunkIndexAdmin indexAdmin, RagProperties props) {
        this.pipeline = pipeline;
        this.metrics = metrics;
        this.audit = audit;
        this.probe = probe;
        this.indexAdmin = indexAdmin;
        this.props = props;
    }

    @PostMapping("/v1/search")
    SearchResponse search(@RequestBody SearchRequest request, @AuthenticationPrincipal Principal principal) {
        var options = request.validated();
        String queryId = "q_" + UlidCreator.getMonotonicUlid();
        var r = pipeline.retrieve(request.query(), options.topK(), options.mode(), options.rerank(), principal);
        metrics.stage("retrieval", r.retrievalMs());
        metrics.stage("rerank", r.rerankMs());

        long probeMs = 0;
        InternalRefusalCause cause = null;
        if (r.hits().isEmpty()) {
            long t0 = System.nanoTime();
            cause = probe.cause(request.query(), principal);
            probeMs = ms(t0);
        }

        AuditRecord record = searchRecord(queryId, request.query(), principal, options, r, cause, probeMs);
        long writeMs;
        try {
            writeMs = audit.write(record);
        } catch (RuntimeException e) {
            metrics.request("search", "error");
            throw e;
        }
        metrics.request("search", "ok");

        return new SearchResponse(queryId, r.hits(), new SearchResponse.Diagnostics(r.bestVectorSimilarity(),
                r.bestRerankProbability(), new SearchResponse.Latency(r.retrievalMs(), r.rerankMs(), probeMs + writeMs)));
    }

    private AuditRecord searchRecord(String queryId, String query, Principal principal, SearchRequest.Options options,
            RetrievalPipeline.Result r, InternalRefusalCause cause, long probeMs) {
        Map<String, String> meta = indexAdmin.aliasInfo(props.retrieval().chunksTarget(props.envPrefix()))
                .map(ChunkIndexAdmin.AliasInfo::meta).orElse(Map.of());
        List<AuditRecord.Hit> hits = new ArrayList<>();
        for (int i = 0; i < r.hits().size(); i++) {
            SearchHit h = r.hits().get(i);
            hits.add(new AuditRecord.Hit(h.chunkId(), h.contentHash(), i + 1, h.score(), h.docId(), h.sectionPath(),
                    h.classification()));
        }
        return new AuditRecord(null, queryId, Instant.now().toString(), audit.traceId(), principal.name(),
                principal.clearance().wire(), "search", query, null,
                new AuditRecord.Retrieval(options.mode().name(), options.topK(), options.rerank(),
                        r.bestVectorSimilarity(), r.bestRerankProbability(), hits),
                null, null, null, null, List.of(), "OK", null, cause == null ? null : cause.name(), null,
                new AuditRecord.Latency(r.retrievalMs(), r.rerankMs(), 0, 0, probeMs),
                meta.get("corpusVersion"), meta.get("chunkerVersion"), meta.get("embeddingModel"));
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
