package rag.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SectionPathTest {

    @Test
    void parseBuildsChain() {
        assertThat(SectionPath.parse("3.2.1").components()).containsExactly("3", "3.2", "3.2.1");
        assertThat(SectionPath.parse("3.2.1").leaf()).isEqualTo("3.2.1");
    }

    @Test
    void emptyStringIsEmptyPath() {
        assertThat(SectionPath.parse("").isEmpty()).isTrue();
        assertThat(SectionPath.empty().leaf()).isEmpty();
    }

    @Test
    void prefixIsComponentWise() {
        assertThat(SectionPath.parse("3.2").isPrefixOf(SectionPath.parse("3.2.1"))).isTrue();
        assertThat(SectionPath.parse("3.2").isPrefixOf(SectionPath.parse("3.20"))).isFalse();
        assertThat(SectionPath.parse("3.2").isPrefixOf(SectionPath.parse("3.2"))).isTrue();
        assertThat(SectionPath.parse("3.2.1").isPrefixOf(SectionPath.parse("3.2"))).isFalse();
    }

    @Test
    void emptyMatchesNothing() {
        assertThat(SectionPath.empty().isPrefixOf(SectionPath.parse("1"))).isFalse();
    }

    @Test
    void compareIsNumeric() {
        assertThat(SectionPath.parse("2.10")).isGreaterThan(SectionPath.parse("2.9"));
        assertThat(SectionPath.parse("2.1")).isGreaterThan(SectionPath.parse("2"));
        assertThat(SectionPath.parse("3")).isGreaterThan(SectionPath.parse("2.9.9"));
        assertThat(SectionPath.parse("1")).isGreaterThan(SectionPath.empty());
    }
}
