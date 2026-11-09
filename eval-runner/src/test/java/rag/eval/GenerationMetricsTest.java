package rag.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import java.util.List;
import org.junit.jupiter.api.Test;
import rag.core.eval.GoldenItem;
import rag.core.model.Clearance;
import rag.eval.AskClient.AskResult;
import rag.eval.GenerationRun.ItemOutcome;
import rag.eval.GenerationRun.SentenceOutcome;
import rag.eval.Sweep.Kind;
import rag.eval.judge.JudgeVerdict;

class GenerationMetricsTest {

    static GoldenItem item(String id, String category, String split) {
        return new GoldenItem(id, "Q " + id, category, List.of(), List.of("p1", "p2"), Clearance.PUBLIC, split);
    }

    static AskResult answered(int prompt, int completion, long genMs) {
        return new AskResult("ANSWERED", null, "ans", List.of(), prompt, completion, genMs);
    }

    static AskResult refused(String reason) {
        return new AskResult("REFUSED", reason, null, List.of(), null, null, 0);
    }

    static SentenceOutcome sentence(boolean flagged, JudgeVerdict v) {
        return new SentenceOutcome("s", List.of(1), flagged ? 0.1 : 0.9, flagged, v);
    }

    GenerationMetrics.Summary dev(List<ItemOutcome> outcomes) {
        return GenerationMetrics.compute(outcomes).stream().filter(s -> s.split().equals("dev")).findFirst().orElseThrow();
    }

    @Test
    void faithfulnessIsSupportedPointsOverAllPointsOfAnsweredItems() {
        var o = List.of(
                new ItemOutcome(item("a", "factual", "dev"), Kind.ANSWERABLE, answered(100, 10, 50),
                        List.of(JudgeVerdict.SUPPORTED, JudgeVerdict.UNSUPPORTED), List.of()),
                new ItemOutcome(item("b", "factual", "dev"), Kind.ANSWERABLE, answered(200, 20, 150),
                        List.of(JudgeVerdict.SUPPORTED, JudgeVerdict.ERROR), List.of()),
                new ItemOutcome(item("c", "factual", "dev"), Kind.ANSWERABLE, refused("LOW_RETRIEVAL_SCORE"), List.of(), List.of()));

        var m = dev(o).byCategory().get("all");

        assertThat(m.points()).isEqualTo(4);
        assertThat(m.supportedPoints()).isEqualTo(2);
        assertThat(m.pointErrors()).isEqualTo(1);
        assertThat(m.faithfulness()).isCloseTo(0.5, offset(1e-9));
        assertThat(m.nCalls()).isEqualTo(3);
        assertThat(m.nAnswered()).isEqualTo(2);
        assertThat(m.falseRefusalRate()).isCloseTo(1.0 / 3, offset(1e-9));
        assertThat(m.meanTotalTokens()).isCloseTo(165.0, offset(1e-9));
    }

    @Test
    void citationSupportAndFlagRates() {
        var o = List.of(new ItemOutcome(item("a", "factual", "dev"), Kind.ANSWERABLE, answered(1, 1, 5), List.of(JudgeVerdict.SUPPORTED, JudgeVerdict.SUPPORTED),
                List.of(sentence(false, JudgeVerdict.SUPPORTED), sentence(true, JudgeVerdict.UNSUPPORTED),
                        sentence(false, JudgeVerdict.SUPPORTED), sentence(false, JudgeVerdict.ERROR))));

        var m = dev(o).byCategory().get("all");

        assertThat(m.sentences()).isEqualTo(4);
        assertThat(m.supportedSentences()).isEqualTo(2);
        assertThat(m.sentenceErrors()).isEqualTo(1);
        assertThat(m.citationSupport()).isCloseTo(0.5, offset(1e-9));
        assertThat(m.softFlagRate()).isCloseTo(0.25, offset(1e-9));
    }

    @Test
    void refusalAccuracyIsSeparateForUnanswerableAndRestricted() {
        var o = List.of(
                new ItemOutcome(item("u1", "unanswerable", "dev"), Kind.UNANSWERABLE, refused("LOW_RETRIEVAL_SCORE"), List.of(), List.of()),
                new ItemOutcome(item("u2", "unanswerable", "dev"), Kind.UNANSWERABLE, answered(1, 1, 1), List.of(), List.of()),
                new ItemOutcome(item("r1", "restricted", "dev"), Kind.BELOW_CLEARANCE, refused("LOW_RETRIEVAL_SCORE"), List.of(), List.of()),
                new ItemOutcome(item("r2", "restricted", "dev"), Kind.BELOW_CLEARANCE, refused("CITATION_VALIDATION_FAILED"), List.of(), List.of()),
                new ItemOutcome(item("r3", "restricted", "dev"), Kind.BELOW_CLEARANCE, answered(1, 1, 1), List.of(), List.of()));

        var s = dev(o);

        assertThat(s.unanswerable().n()).isEqualTo(2);
        assertThat(s.unanswerable().correct()).isEqualTo(1);
        assertThat(s.unanswerable().accuracy()).isCloseTo(0.5, offset(1e-9));
        assertThat(s.restricted().n()).isEqualTo(3);
        assertThat(s.restricted().correct()).isEqualTo(2);
    }

    @Test
    void validationFailuresAndBreakdownByCategoryAndSplit() {
        var o = List.of(
                new ItemOutcome(item("a", "factual", "dev"), Kind.ANSWERABLE, refused("CITATION_VALIDATION_FAILED"), List.of(), List.of()),
                new ItemOutcome(item("b", "multi-hop", "dev"), Kind.ANSWERABLE, answered(1, 1, 10), List.of(JudgeVerdict.SUPPORTED, JudgeVerdict.SUPPORTED), List.of()),
                new ItemOutcome(item("c", "factual", "test"), Kind.ANSWERABLE, answered(1, 1, 30), List.of(JudgeVerdict.UNSUPPORTED, JudgeVerdict.UNSUPPORTED), List.of()));

        var all = GenerationMetrics.compute(o);
        var devSummary = all.stream().filter(s -> s.split().equals("dev")).findFirst().orElseThrow();
        var testSummary = all.stream().filter(s -> s.split().equals("test")).findFirst().orElseThrow();

        assertThat(devSummary.byCategory().get("all").validationFailures()).isEqualTo(1);
        assertThat(devSummary.byCategory().get("multi-hop").faithfulness()).isCloseTo(1.0, offset(1e-9));
        assertThat(devSummary.byCategory().get("multi-hop").nCalls()).isEqualTo(1);
        assertThat(testSummary.byCategory().get("all").faithfulness()).isCloseTo(0.0, offset(1e-9));
    }

    @Test
    void latencyPercentilesOverAnsweredCalls() {
        var o = List.of(
                new ItemOutcome(item("a", "factual", "dev"), Kind.ANSWERABLE, answered(1, 1, 10), List.of(), List.of()),
                new ItemOutcome(item("b", "factual", "dev"), Kind.ANSWERABLE, answered(1, 1, 20), List.of(), List.of()),
                new ItemOutcome(item("c", "factual", "dev"), Kind.ANSWERABLE, answered(1, 1, 90), List.of(), List.of()));

        var m = dev(o).byCategory().get("all");

        assertThat(m.genP50()).isEqualTo(20);
        assertThat(m.genP95()).isEqualTo(90);
    }

    @Test
    void requestErrorsAreCountedAndLeaveTheFalseRefusalDenominator() {
        var err = new AskResult("ERROR", "request failed", null, List.of(), null, null, 0);
        var o = List.of(
                new ItemOutcome(item("a", "factual", "dev"), Kind.ANSWERABLE, refused("LOW_RETRIEVAL_SCORE"), List.of(), List.of()),
                new ItemOutcome(item("b", "factual", "dev"), Kind.ANSWERABLE, answered(1, 1, 5), List.of(), List.of()),
                new ItemOutcome(item("c", "factual", "dev"), Kind.ANSWERABLE, err, List.of(), List.of()),
                new ItemOutcome(item("d", "factual", "dev"), Kind.ANSWERABLE, err, List.of(), List.of()));

        var m = dev(o).byCategory().get("all");

        assertThat(m.askErrors()).isEqualTo(2);
        assertThat(m.falseRefusalRate()).isCloseTo(0.5, offset(1e-9));
    }

    @Test
    void overlapFlagsAreCrossedWithJudgeVerdicts() {
        var o = List.of(new ItemOutcome(item("a", "factual", "dev"), Kind.ANSWERABLE, answered(1, 1, 5), List.of(),
                List.of(sentence(true, JudgeVerdict.UNSUPPORTED), sentence(true, JudgeVerdict.SUPPORTED),
                        sentence(false, JudgeVerdict.SUPPORTED), sentence(false, JudgeVerdict.SUPPORTED),
                        sentence(false, JudgeVerdict.UNSUPPORTED), sentence(true, JudgeVerdict.ERROR))));

        var t = dev(o).byCategory().get("all").overlapVsJudge();

        assertThat(t.flaggedUnsupported()).isEqualTo(1);
        assertThat(t.flaggedSupported()).isEqualTo(1);
        assertThat(t.unflaggedSupported()).isEqualTo(2);
        assertThat(t.unflaggedUnsupported()).isEqualTo(1);
    }
}
