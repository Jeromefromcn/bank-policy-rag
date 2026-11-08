package rag.core.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SourceMatcherTest {

    @Test
    void sectionPathPrefixMatches() {
        assertThat(SourceMatcher.matches(new ExpectedSource("d", "3.2"), new RankedHit("d", List.of("3", "3.2", "3.2.1"), List.of())))
                .isTrue();
    }

    @Test
    void coveredSectionCounts() {
        assertThat(SourceMatcher.matches(new ExpectedSource("d", "2.3"),
                new RankedHit("d", List.of("2", "2.2"), List.of("2.2", "2.3.1")))).isTrue();
    }

    @Test
    void noFalsePrefix() {
        assertThat(SourceMatcher.matches(new ExpectedSource("d", "3.2"), new RankedHit("d", List.of("3", "3.20"), List.of("3.20"))))
                .isFalse();
    }

    @Test
    void docIdMustMatch() {
        assertThat(SourceMatcher.matches(new ExpectedSource("d", "3"), new RankedHit("e", List.of("3"), List.of("3")))).isFalse();
    }

    @Test
    void hitWithoutSectionMatchesNothing() {
        assertThat(SourceMatcher.matches(new ExpectedSource("d", "1"), new RankedHit("d", List.of(), List.of()))).isFalse();
    }
}
