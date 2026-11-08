package rag.core.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import rag.core.model.SectionMarker;

class SpmSectionMarkersTest {

    static final String DOC = """
            Purpose
              Self-made purpose text.
            Structure
              1.  Introduction
                  1.1  Background
              2.  Controls
            1.  Introduction
                1.1  Background text here.
                1.1.1  Detail text.
            1 This is a footnote line.
            2.  Controls
                2.1  Control text.
                1.5  wrapped line starting with a number
            """;

    @Test
    void markersStartAtBodyAndFollowNumbering() {
        assertThat(SpmSectionMarkers.find(DOC)).extracting(m -> m.path().leaf())
                .containsExactly("1", "1.1", "1.1.1", "2", "2.1");
    }

    @Test
    void markersCarryTheFullChain() {
        assertThat(SpmSectionMarkers.find(DOC).get(2).path().components()).containsExactly("1", "1.1", "1.1.1");
    }

    @Test
    void markerOffsetsPointAtTheNumber() {
        for (SectionMarker m : SpmSectionMarkers.find(DOC)) {
            assertThat(DOC.substring(m.offset())).startsWith(m.path().leaf());
        }
        SectionMarker first = SpmSectionMarkers.find(DOC).getFirst();
        assertThat(first.offset()).isGreaterThan(DOC.indexOf("2.  Controls"));
    }

    @Test
    void failsWhenStructureLineMissing() {
        assertThatThrownBy(() -> SpmSectionMarkers.find("1.  Intro\n1.1  Text\n"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("body start");
    }

    @Test
    void failsWhenBodyStartNotFound() {
        assertThatThrownBy(() -> SpmSectionMarkers.find("Structure\n 1. Only toc\n"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("body start");
    }
}
