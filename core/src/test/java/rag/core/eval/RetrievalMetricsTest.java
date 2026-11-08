package rag.core.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RetrievalMetricsTest {

    static final ExpectedSource A = new ExpectedSource("a", "1");
    static final ExpectedSource B = new ExpectedSource("b", "2");
    static final RankedHit HIT_A = new RankedHit("a", List.of("1", "1.1"), List.of("1.1"));
    static final RankedHit HIT_B = new RankedHit("b", List.of("2"), List.of("2"));
    static final RankedHit MISS = new RankedHit("x", List.of("9"), List.of("9"));

    static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }

    static List<RankedHit> ranked(RankedHit... hits) {
        List<RankedHit> list = new ArrayList<>(List.of(hits));
        while (list.size() < 10) {
            list.add(MISS);
        }
        return list;
    }

    @Test
    void twoExpectedOneFoundAtRank3() {
        var s = RetrievalMetrics.score(List.of(A, B), ranked(MISS, MISS, HIT_A, HIT_A));
        assertThat(s.recall5()).isEqualTo(0.5);
        assertThat(s.recall10()).isEqualTo(0.5);
        assertThat(s.reciprocalRank()).isCloseTo(1.0 / 3, offset(1e-9));
        assertThat(s.ndcg10()).isCloseTo((1 / log2(4)) / (1 / log2(2) + 1 / log2(3)), offset(1e-9));
        assertThat(s.firstMatchRank()).isEqualTo(3);
    }

    @Test
    void bothFoundAtTop() {
        var s = RetrievalMetrics.score(List.of(A, B), ranked(HIT_B, HIT_A));
        assertThat(s.recall5()).isEqualTo(1.0);
        assertThat(s.reciprocalRank()).isEqualTo(1.0);
        assertThat(s.ndcg10()).isCloseTo(1.0, offset(1e-9));
    }

    @Test
    void foundOnlyAtRank7() {
        var s = RetrievalMetrics.score(List.of(A), ranked(MISS, MISS, MISS, MISS, MISS, MISS, HIT_A));
        assertThat(s.recall5()).isZero();
        assertThat(s.recall10()).isEqualTo(1.0);
        assertThat(s.reciprocalRank()).isCloseTo(1.0 / 7, offset(1e-9));
    }

    @Test
    void nothingFound() {
        var s = RetrievalMetrics.score(List.of(A), ranked());
        assertThat(s.recall5()).isZero();
        assertThat(s.recall10()).isZero();
        assertThat(s.reciprocalRank()).isZero();
        assertThat(s.ndcg10()).isZero();
        assertThat(s.firstMatchRank()).isNull();
    }

    @Test
    void ranksBeyondTenAreIgnored() {
        List<RankedHit> hits = new ArrayList<>(ranked());
        hits.add(HIT_A);
        var s = RetrievalMetrics.score(List.of(A), hits);
        assertThat(s.recall10()).isZero();
        assertThat(s.reciprocalRank()).isZero();
    }

    @Test
    void emptyExpectedIsRejected() {
        assertThatThrownBy(() -> RetrievalMetrics.score(List.of(), ranked())).isInstanceOf(IllegalArgumentException.class);
    }
}
