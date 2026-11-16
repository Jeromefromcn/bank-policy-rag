package rag.app.search;

import com.github.f4b6a3.ulid.UlidCreator;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import rag.app.metrics.RequestMetrics;
import rag.app.security.Principal;

@RestController
public class SearchController {

    private final RetrievalPipeline pipeline;
    private final RequestMetrics metrics;

    public SearchController(RetrievalPipeline pipeline, RequestMetrics metrics) {
        this.pipeline = pipeline;
        this.metrics = metrics;
    }

    @PostMapping("/v1/search")
    SearchResponse search(@RequestBody SearchRequest request, @AuthenticationPrincipal Principal principal) {
        var options = request.validated();
        var r = pipeline.retrieve(request.query(), options.topK(), options.mode(), options.rerank(), principal);
        metrics.stage("retrieval", r.retrievalMs());
        metrics.stage("rerank", r.rerankMs());
        metrics.request("search", "ok");
        return new SearchResponse("q_" + UlidCreator.getMonotonicUlid(), r.hits(), new SearchResponse.Diagnostics(
                r.bestVectorSimilarity(), r.bestRerankProbability(), new SearchResponse.Latency(r.retrievalMs(), r.rerankMs())));
    }
}
