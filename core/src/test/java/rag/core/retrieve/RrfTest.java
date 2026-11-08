package rag.core.retrieve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RrfTest {

    @Test
    void sumsReciprocalRanksAcrossLegs() {
        // a: vector 1, bm25 2 -> 1/61 + 1/62; b: vector 2, bm25 1 -> 1/62 + 1/61 (tie); c: vector 3 only
        var out = Rrf.fuse(List.of(List.of("a", "b", "c"), List.of("b", "a")), 60, 10);

        assertThat(out).extracting(RankedId::id).containsExactly("a", "b", "c");
        assertThat(out.get(0).score()).isCloseTo(1.0 / 61 + 1.0 / 62, offset(1e-12));
        assertThat(out.get(2).score()).isCloseTo(1.0 / 63, offset(1e-12));
    }

    @Test
    void recordsRankPerLegAndOmitsLegsWithoutTheId() {
        var out = Rrf.fuse(List.of(List.of("a", "b"), List.of("b")), 60, 10);

        assertThat(out.get(0).id()).isEqualTo("b");
        assertThat(out.get(0).legRanks()).isEqualTo(Map.of(0, 2, 1, 1));
        assertThat(out.get(1).legRanks()).isEqualTo(Map.of(0, 1));
    }

    @Test
    void tieBreaksOnBestSingleLegRankThenId() {
        // x: vector 1 only (1/61); y: bm25 1 only (1/61); z: vector 2 only (1/62)
        var out = Rrf.fuse(List.of(List.of("x", "z"), List.of("y")), 60, 10);
        assertThat(out).extracting(RankedId::id).containsExactly("x", "y", "z");

        // same score and same best rank: id ascending
        var out2 = Rrf.fuse(List.of(List.of("b"), List.of("a")), 60, 10);
        assertThat(out2).extracting(RankedId::id).containsExactly("a", "b");
    }

    @Test
    void oneEmptyLegReturnsTheOtherUnchanged() {
        var out = Rrf.fuse(List.of(List.of("a", "b", "c"), List.of()), 60, 10);
        assertThat(out).extracting(RankedId::id).containsExactly("a", "b", "c");
    }

    @Test
    void bothEmptyReturnsNothing() {
        assertThat(Rrf.fuse(List.of(List.of(), List.of()), 60, 10)).isEmpty();
    }

    @Test
    void truncatesToTopK() {
        var out = Rrf.fuse(List.of(List.of("a", "b", "c", "d")), 60, 2);
        assertThat(out).extracting(RankedId::id).containsExactly("a", "b");
    }

    @Test
    void duplicateInsideOneLegCountsFirstRankOnly() {
        var out = Rrf.fuse(List.of(List.of("a", "b", "a")), 60, 10);
        assertThat(out.get(0).score()).isCloseTo(1.0 / 61, offset(1e-12));
        assertThat(out.get(0).legRanks()).isEqualTo(Map.of(0, 1));
    }

    @Test
    void usesTheGivenK() {
        var out = Rrf.fuse(List.of(List.of("a")), 10, 10);
        assertThat(out.getFirst().score()).isCloseTo(1.0 / 11, offset(1e-12));
    }
}
