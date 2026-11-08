package rag.core.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import rag.core.model.Clearance;
import rag.core.model.SourceType;

class MarkdownParserTest {

    static final String MD = """
            ---
            docId: eb-sample
            title: Sample Standard
            version: "1.0"
            classification: internal
            sourceType: internal
            fictional: true
            ---
            *Fictional.*
            # Sample Standard
            ## 1 Scope
            1.1 Numbered paragraph, not a section.
            ## 2 Rules
            ### 2.1 Detail
            """;

    @Test
    void readsFrontMatter() {
        var d = MarkdownParser.parse(MD, "x.md");
        assertThat(d.docId()).isEqualTo("eb-sample");
        assertThat(d.title()).isEqualTo("Sample Standard");
        assertThat(d.docVersion()).isEqualTo("1.0");
        assertThat(d.classification()).isEqualTo(Clearance.INTERNAL);
        assertThat(d.sourceType()).isEqualTo(SourceType.INTERNAL);
        assertThat(d.text()).doesNotContain("docId:").startsWith("*Fictional.*");
        assertThat(d.pages()).isEmpty();
    }

    @Test
    void headingNumbersAreMarkers() {
        var d = MarkdownParser.parse(MD, "x.md");
        assertThat(d.markers()).extracting(m -> m.path().leaf()).containsExactly("1", "2", "2.1");
        assertThat(d.text().substring(d.markers().get(1).offset())).startsWith("## 2 Rules");
    }

    @Test
    void crlfLineEndingsParse() {
        var d = MarkdownParser.parse(MD.replace("\n", "\r\n"), "x.md");
        assertThat(d.markers()).hasSize(3);
        assertThat(d.text()).doesNotContain("\r");
    }

    @Test
    void missingClassificationFails() {
        assertThatThrownBy(() -> MarkdownParser.parse(MD.replace("classification: internal\n", ""), "x.md"))
                .hasMessageContaining("classification").hasMessageContaining("x.md");
    }

    @Test
    void unknownClassificationFails() {
        assertThatThrownBy(() -> MarkdownParser.parse(MD.replace("classification: internal", "classification: secret"), "x.md"))
                .hasMessageContaining("secret").hasMessageContaining("x.md");
    }

    @Test
    void missingFrontMatterFails() {
        assertThatThrownBy(() -> MarkdownParser.parse("# Title\n## 1 Scope\n", "x.md"))
                .hasMessageContaining("front matter").hasMessageContaining("x.md");
    }
}
