package rag.core.cite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import java.util.List;
import org.junit.jupiter.api.Test;

class CitationValidatorTest {

    private final CitationValidator validator = new CitationValidator(0.5);

    @Test
    void acceptsInRangeCitations() {
        var r = validator.validate("A tolerance is a limit [1].", List.of("A tolerance is a limit on disruption.", "Other."));

        assertThat(r.rangeOk()).isTrue();
        assertThat(r.coverageOk()).isTrue();
        assertThat(r.citedRefs()).containsExactly(1);
        assertThat(r.hardFail()).isFalse();
    }

    @Test
    void rejectsOutOfRangeRef() {
        assertThat(validator.validate("Claim [3].", List.of("a", "b")).rangeOk()).isFalse();
        assertThat(validator.validate("Claim [0].", List.of("a", "b")).rangeOk()).isFalse();
    }

    @Test
    void rejectsUncitedParagraph() {
        var r = validator.validate("First claim [1].\n\nSecond claim without a citation.", List.of("first claim"));

        assertThat(r.coverageOk()).isFalse();
        assertThat(r.hardFail()).isTrue();
    }

    @Test
    void listItemsAreOwnParagraphs() {
        var r = validator.validate("- first item [1]\n- second item", List.of("first item second item"));

        assertThat(r.coverageOk()).isFalse();
    }

    @Test
    void noCitationsFailsCoverage() {
        assertThat(validator.validate("A plain answer.", List.of("a")).coverageOk()).isFalse();
    }

    @Test
    void parsesAdjacentAndCommaForms() {
        assertThat(validator.validate("One [1][3].", List.of("a", "b", "c")).citedRefs()).containsExactly(1, 3);
        assertThat(validator.validate("Two [1, 2].", List.of("a", "b", "c")).citedRefs()).containsExactly(1, 2);
    }

    @Test
    void stripsMarkersBeforeOverlap() {
        var r = validator.validate("Board approves [1].", List.of("The board approves it."));

        assertThat(r.sentences()).hasSize(1);
        assertThat(r.sentences().get(0).overlap()).isCloseTo(1.0, offset(1e-9));
        assertThat(r.sentences().get(0).flagged()).isFalse();
    }

    @Test
    void flagsLowOverlapSentenceWithoutHardFail() {
        var r = validator.validate("Quarterly dividends exceed projected revenue forecasts [1].",
                List.of("The committee reviews revenue annually."));

        assertThat(r.sentences().get(0).flagged()).isTrue();
        assertThat(r.hardFail()).isFalse();
    }

    @Test
    void splitsSentencesOnPeriodAndLineBreak() {
        var r = validator.validate("First sentence [1]. Second sentence [1].\nThird line [1]", List.of("first second third sentence line"));

        assertThat(r.sentences()).hasSize(3);
    }

    @Test
    void isDeterministic() {
        var sources = List.of("The board approves tolerance levels.");
        assertThat(validator.validate("Board approves [1].", sources)).isEqualTo(validator.validate("Board approves [1].", sources));
    }
}
