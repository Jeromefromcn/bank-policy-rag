package rag.app.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import rag.app.embed.EmbeddingUnavailableException;
import rag.app.security.Principal;
import rag.core.model.Clearance;

class HybridSearchTest {

    static final Principal P = new Principal("alice", Clearance.RESTRICTED, Set.of());
    static final int LEG_SIZE = 50;

    final VectorRetriever vector = mock(VectorRetriever.class);
    final Bm25Retriever bm25 = mock(Bm25Retriever.class);
    final HybridSearch search = new HybridSearch(vector, bm25, LEG_SIZE);

    static SearchHit hit(String id, Map<String, Object> scores) {
        return new SearchHit(id, "s-" + id, "doc", "Title", List.of("1"), List.of("1"), List.of(), "public", 0.5, scores, "text " + id);
    }

    static SearchHit vec(String id, int rank) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("vectorRank", rank);
        m.put("similarity", 0.9 - rank / 100.0);
        return hit(id, m);
    }

    static SearchHit bm(String id, int rank) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bm25Rank", rank);
        m.put("bm25Score", 10.0 - rank);
        return hit(id, m);
    }

    @Test
    void fusesBothLegsByReciprocalRank() {
        when(vector.search(eq("q"), anyInt(), any())).thenReturn(List.of(vec("a", 1), vec("b", 2), vec("c", 3)));
        when(bm25.search(eq("q"), anyInt(), any())).thenReturn(List.of(bm("b", 1), bm("d", 2)));

        var hits = search.search("q", 10, Mode.HYBRID, P);

        // b: 1/62 + 1/61 beats a: 1/61; then d (bm25 rank 2, 1/62) before c (vector rank 3, 1/63)
        assertThat(hits).extracting(SearchHit::chunkId).containsExactly("b", "a", "d", "c");
        assertThat(hits.getFirst().score()).isCloseTo(1.0 / 62 + 1.0 / 61, org.assertj.core.api.Assertions.offset(1e-12));
    }

    @Test
    void scoresCarryTheRankOfEachLegThatReturnedTheHit() {
        when(vector.search(eq("q"), anyInt(), any())).thenReturn(List.of(vec("a", 1), vec("b", 2)));
        when(bm25.search(eq("q"), anyInt(), any())).thenReturn(List.of(bm("b", 1)));

        var hits = search.search("q", 10, Mode.HYBRID, P);

        var b = hits.get(0);
        assertThat(b.chunkId()).isEqualTo("b");
        assertThat(b.scores()).containsEntry("vectorRank", 2).containsEntry("bm25Rank", 1).containsKeys("similarity", "bm25Score");
        var a = hits.get(1);
        assertThat(a.scores()).containsEntry("vectorRank", 1).doesNotContainKeys("bm25Rank", "bm25Score");
    }

    @Test
    void emptyBm25LegEqualsTheVectorOrder() {
        when(vector.search(eq("q"), anyInt(), any())).thenReturn(List.of(vec("a", 1), vec("b", 2), vec("c", 3)));
        when(bm25.search(eq("q"), anyInt(), any())).thenReturn(List.of());

        assertThat(search.search("q", 10, Mode.HYBRID, P)).extracting(SearchHit::chunkId).containsExactly("a", "b", "c");
    }

    @Test
    void emptyVectorLegEqualsTheBm25Order() {
        when(vector.search(eq("q"), anyInt(), any())).thenReturn(List.of());
        when(bm25.search(eq("q"), anyInt(), any())).thenReturn(List.of(bm("x", 1), bm("y", 2)));

        assertThat(search.search("q", 10, Mode.HYBRID, P)).extracting(SearchHit::chunkId).containsExactly("x", "y");
    }

    @Test
    void truncatesToTopKAndAsksEachLegForTheLegSize() {
        when(vector.search(eq("q"), anyInt(), any())).thenReturn(List.of(vec("a", 1), vec("b", 2), vec("c", 3)));
        when(bm25.search(eq("q"), anyInt(), any())).thenReturn(List.of());

        assertThat(search.search("q", 2, Mode.HYBRID, P)).hasSize(2);
        verify(vector).search("q", LEG_SIZE, P);
        verify(bm25).search("q", LEG_SIZE, P);
    }

    @Test
    void aLargeTopKRaisesTheLegSize() {
        when(vector.search(eq("q"), anyInt(), any())).thenReturn(List.of());
        when(bm25.search(eq("q"), anyInt(), any())).thenReturn(List.of());

        search.search("q", 60, Mode.HYBRID, P);

        verify(vector).search("q", 60, P);
        verify(bm25).search("q", 60, P);
    }

    @Test
    void vectorModeNeverTouchesBm25AndKeepsTheVectorHitsAsTheyAre() {
        var hits = List.of(vec("a", 1), vec("b", 2));
        when(vector.search("q", 5, P)).thenReturn(hits);

        assertThat(search.search("q", 5, Mode.VECTOR, P)).isEqualTo(hits);
        verifyNoInteractions(bm25);
    }

    @Test
    void bm25ModeNeverTouchesTheVectorLeg() {
        var hits = List.of(bm("a", 1));
        when(bm25.search("q", 5, P)).thenReturn(hits);

        assertThat(search.search("q", 5, Mode.BM25, P)).isEqualTo(hits);
        verifyNoInteractions(vector);
    }

    @Test
    void hybridFailsWhenTheEmbedderIsDownInsteadOfFallingBackToBm25() {
        when(vector.search(eq("q"), anyInt(), any())).thenThrow(new EmbeddingUnavailableException("down", null));
        when(bm25.search(eq("q"), anyInt(), any())).thenReturn(List.of(bm("a", 1)));

        assertThatThrownBy(() -> search.search("q", 5, Mode.HYBRID, P)).isInstanceOf(EmbeddingUnavailableException.class);
        verify(bm25, never()).search(any(), anyInt(), any());
    }

    @Test
    void modeParsing() {
        assertThat(Mode.parse(null)).isEqualTo(Mode.HYBRID);
        assertThat(Mode.parse("BM25")).isEqualTo(Mode.BM25);
        assertThatThrownBy(() -> Mode.parse("bm25")).hasMessageContaining("VECTOR");
        assertThatThrownBy(() -> Mode.parse("FUZZY")).hasMessageContaining("HYBRID");
    }
}
