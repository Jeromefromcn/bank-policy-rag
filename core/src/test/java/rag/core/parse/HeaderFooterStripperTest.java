package rag.core.parse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class HeaderFooterStripperTest {

    private static final String H1 = "Policy Manual";
    private static final String H2 = "AB-1   Sample Module   V.1 - 01.01.2020";

    @Test
    void removesRecurringHeaderAndPageNumbers() {
        var pages = List.of(
                List.of(H1, H2, "1. Intro", "text a", "1"),
                List.of(H1, H2, "text b", "2"),
                List.of(H1, H2, "text c", "3"));
        assertThat(HeaderFooterStripper.strip(pages)).containsExactly(
                List.of("1. Intro", "text a"), List.of("text b"), List.of("text c"));
    }

    @Test
    void keepsBodyLineThatAppearsOnFewPages() {
        var pages = List.of(
                List.of(H1, "Note: only here", "body 1", "more 1", "x 1", "1"),
                List.of(H1, "body 2", "more 2", "y 2", "z 2", "2"),
                List.of(H1, "body 3", "more 3", "w 3", "v 3", "3"));
        assertThat(HeaderFooterStripper.strip(pages).getFirst()).contains("Note: only here");
    }

    @Test
    void digitNormalisationCatchesChangingNumbersInHeaders() {
        var pages = List.of(
                List.of("Module AB-1 page 1 of 3", "alpha", "beta", "gamma"),
                List.of("Module AB-1 page 2 of 3", "delta", "epsilon", "zeta"),
                List.of("Module AB-1 page 3 of 3", "eta", "theta", "iota"));
        assertThat(HeaderFooterStripper.strip(pages)).allSatisfy(p -> assertThat(p).noneMatch(l -> l.startsWith("Module")));
    }

    @Test
    void blankLinesDoNotCountTowardsZonesAndAreTrimmedAtPageEdges() {
        var pages = List.of(
                List.of("", H1, "a", "b", "c", "", "1", ""),
                List.of(H1, "d", "e", "f", "2"));
        assertThat(HeaderFooterStripper.strip(pages)).containsExactly(List.of("a", "b", "c"), List.of("d", "e", "f"));
    }
}
