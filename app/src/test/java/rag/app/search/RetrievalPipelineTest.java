package rag.app.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import rag.app.security.Principal;
import rag.app.support.StubReranker;
import rag.core.chunk.TokenCounter;
import rag.core.model.Clearance;
import rag.core.rerank.RerankOrder;

class RetrievalPipelineTest {

    static final Principal P = new Principal("alice", Clearance.RESTRICTED, Set.of());
    static final TokenCounter TC = TokenCounter.cl100k();

    final HybridSearch hybrid = mock(HybridSearch.class);
    final StubReranker reranker = new StubReranker();
    final RetrievalPipeline pipeline = new RetrievalPipeline(hybrid, reranker, TC, 20, 256);

    static SearchHit hit(int i, String text) {
        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put("vectorRank", i + 1);
        scores.put("similarity", 0.8);
        return new SearchHit("c" + i, "s" + i, "doc", "Title", List.of("1"), List.of("1"), List.of(), "public",
                1.0 / (61 + i), scores, text, "h" + i);
    }

    static List<SearchHit> hits(int n) {
        List<SearchHit> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(hit(i, "passage " + i));
        }
        return out;
    }

    void hybridReturns(int n) {
        when(hybrid.searchWithDiagnostics(eq("q"), anyInt(), any(), any())).thenAnswer(inv -> {
            int k = inv.getArgument(1);
            return new HybridSearch.SearchOutcome(hits(Math.min(n, k)), 0.8);
        });
    }

    @Test
    void rerankFalseReturnsHybridResultUnchanged() {
        hybridReturns(30);
        var r = pipeline.retrieve("q", 5, Mode.HYBRID, false, P);
        assertThat(r.hits()).isEqualTo(hits(5));
        assertThat(r.bestVectorSimilarity()).isEqualTo(0.8);
        assertThat(r.bestRerankProbability()).isNull();
        assertThat(r.rerankMs()).isZero();
        assertThat(reranker.calls()).isEmpty();
        verify(hybrid).searchWithDiagnostics("q", 5, Mode.HYBRID, P);
    }

    @Test
    void rerankRequestsTwentyCandidates() {
        hybridReturns(30);
        pipeline.retrieve("q", 5, Mode.HYBRID, true, P);
        verify(hybrid).searchWithDiagnostics("q", 20, Mode.HYBRID, P);
        assertThat(reranker.calls()).singleElement().satisfies(c -> assertThat(c.passages()).hasSize(20));
    }

    @Test
    void rerankReordersAndTruncatesToTopK() {
        hybridReturns(30);
        reranker.scoreWith((q, p) -> p.equals("passage 7") ? 9.0 : -1.0);
        var r = pipeline.retrieve("q", 5, Mode.HYBRID, true, P);
        assertThat(r.hits()).hasSize(5);
        SearchHit first = r.hits().getFirst();
        assertThat(first.chunkId()).isEqualTo("c7");
        assertThat(first.scores()).containsEntry("fusedRank", 8).containsEntry("rerankScore", 9.0)
                .containsEntry("rerankProbability", RerankOrder.sigmoid(9.0)).containsEntry("vectorRank", 8);
        assertThat(first.score()).isEqualTo(RerankOrder.sigmoid(9.0));
        // ties keep the fused order
        assertThat(r.hits().subList(1, 5)).extracting(SearchHit::chunkId).containsExactly("c0", "c1", "c2", "c3");
        assertThat(r.bestRerankProbability()).isEqualTo(RerankOrder.sigmoid(9.0));
        assertThat(r.bestVectorSimilarity()).isEqualTo(0.8);
    }

    @Test
    void candidatesGrowToTopK() {
        hybridReturns(60);
        var r = pipeline.retrieve("q", 50, Mode.HYBRID, true, P);
        verify(hybrid).searchWithDiagnostics("q", 50, Mode.HYBRID, P);
        assertThat(r.hits()).hasSize(50);
    }

    @Test
    void passagesAreTruncatedTo256Tokens() {
        String longText = IntStream.range(0, 80).mapToObj(i -> "Failover step " + i + " is logged.")
                .collect(Collectors.joining(" "));
        assertThat(TC.count(longText)).isGreaterThan(400);
        when(hybrid.searchWithDiagnostics(eq("q"), anyInt(), any(), any()))
                .thenReturn(new HybridSearch.SearchOutcome(List.of(hit(0, longText)), 0.8));
        var r = pipeline.retrieve("q", 5, Mode.HYBRID, true, P);
        String sent = reranker.calls().getFirst().passages().getFirst();
        assertThat(TC.count(sent)).isEqualTo(256);
        assertThat(r.hits().getFirst().text()).isEqualTo(longText);
    }

    @Test
    void emptyHitsSkipReranker() {
        hybridReturns(0);
        var r = pipeline.retrieve("q", 5, Mode.HYBRID, true, P);
        assertThat(r.hits()).isEmpty();
        assertThat(r.bestRerankProbability()).isNull();
        assertThat(reranker.calls()).isEmpty();
    }

    @Test
    void rerankWorksInVectorMode() {
        hybridReturns(30);
        pipeline.retrieve("q", 5, Mode.VECTOR, true, P);
        verify(hybrid).searchWithDiagnostics("q", 20, Mode.VECTOR, P);
    }
}
