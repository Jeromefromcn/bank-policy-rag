package rag.core.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import rag.core.model.Clearance;
import rag.core.model.PageSpan;
import rag.core.model.ParsedDocument;
import rag.core.model.SectionMarker;
import rag.core.model.SectionPath;
import rag.core.model.SourceType;

class SectionParentsTest {

    private static final String TEXT = "Preface.\n1. First\nbody one\n1.1 Sub\nbody sub\n2. Second\nbody two\n";

    private static int at(String needle) {
        return TEXT.indexOf(needle);
    }

    private static ParsedDocument doc(List<SectionMarker> markers, List<PageSpan> pages) {
        return new ParsedDocument("d-1", "Doc", "1", SourceType.REGULATORY, Clearance.PUBLIC, TEXT, pages, markers);
    }

    private static final List<SectionMarker> MARKERS = List.of(
            new SectionMarker(at("1. First"), SectionPath.parse("1")),
            new SectionMarker(at("1.1 Sub"), SectionPath.parse("1.1")),
            new SectionMarker(at("2. Second"), SectionPath.parse("2")));

    @Test
    void oneParentPerDepthOneSectionWithItsFullText() {
        var parents = SectionParents.of(doc(MARKERS, List.of()));

        assertThat(parents).hasSize(2);
        assertThat(parents.get(0).path().components()).containsExactly("1");
        assertThat(parents.get(0).content()).isEqualTo("1. First\nbody one\n1.1 Sub\nbody sub");
        assertThat(parents.get(1).content()).isEqualTo("2. Second\nbody two");
        assertThat(parents.get(0).docId()).isEqualTo("d-1");
    }

    @Test
    void sectionIdUsesTheDepthOnePath() {
        var parents = SectionParents.of(doc(MARKERS, List.of()));

        assertThat(parents.get(0).sectionId()).isEqualTo(rag.core.ids.Hashes.sectionId("d-1", SectionPath.parse("1")));
    }

    @Test
    void pagesComeFromSpansOverlappingTheParent() {
        var pages = List.of(new PageSpan(1, 0, at("2. Second")), new PageSpan(2, at("2. Second"), TEXT.length()));

        var parents = SectionParents.of(doc(MARKERS, pages));

        assertThat(parents.get(0).pages()).containsExactly(1);
        assertThat(parents.get(1).pages()).containsExactly(2);
    }

    @Test
    void documentWithoutMarkersHasOneParentWithAnEmptyPath() {
        var parents = SectionParents.of(doc(List.of(), List.of()));

        assertThat(parents).hasSize(1);
        assertThat(parents.getFirst().path().isEmpty()).isTrue();
        assertThat(parents.getFirst().content()).startsWith("Preface.").endsWith("body two");
    }
}
