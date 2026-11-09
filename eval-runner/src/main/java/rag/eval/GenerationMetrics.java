package rag.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import rag.eval.GenerationRun.ItemOutcome;
import rag.eval.Sweep.Kind;
import rag.eval.judge.JudgeVerdict;

/** Generation-side metrics (spec 03 §7.3, spec 00 §6.3). ERROR verdicts are never supported but stay in the denominator. */
public final class GenerationMetrics {

    /** Flagged = overlap below the run's threshold; ERROR verdicts are excluded. */
    public record OverlapTable(int flaggedSupported, int flaggedUnsupported, int unflaggedSupported, int unflaggedUnsupported) {}

    /** {@code falseRefusalRate} is over calls that got an answer or a refusal; {@code askErrors} are request failures. */
    public record Metrics(int nCalls, int askErrors, OverlapTable overlapVsJudge, int nAnswered, Double falseRefusalRate, int points, int supportedPoints, int pointErrors,
            Double faithfulness, int sentences, int supportedSentences, int sentenceErrors, Double citationSupport,
            Double softFlagRate, int validationFailures, Double meanTotalTokens, Long genP50, Long genP95) {}

    public record Refusal(int n, int correct, Double accuracy) {}

    public record Summary(String split, Map<String, Metrics> byCategory, Refusal unanswerable, Refusal restricted) {}

    private GenerationMetrics() {}

    public static List<Summary> compute(List<ItemOutcome> outcomes) {
        List<Summary> out = new ArrayList<>();
        for (String split : List.of("test", "dev")) {
            List<ItemOutcome> inSplit = outcomes.stream().filter(o -> o.item().split().equals(split)).toList();
            if (inSplit.isEmpty()) {
                continue;
            }
            List<ItemOutcome> answerable = inSplit.stream().filter(o -> o.kind() == Kind.ANSWERABLE).toList();
            Map<String, Metrics> byCategory = new LinkedHashMap<>();
            byCategory.put("all", metrics(answerable));
            for (String category : answerable.stream().map(o -> o.item().category()).distinct().sorted().toList()) {
                byCategory.put(category, metrics(answerable.stream().filter(o -> o.item().category().equals(category)).toList()));
            }
            out.add(new Summary(split, byCategory, refusal(inSplit, Kind.UNANSWERABLE), refusal(inSplit, Kind.BELOW_CLEARANCE)));
        }
        return out;
    }

    private static Refusal refusal(List<ItemOutcome> outcomes, Kind kind) {
        List<ItemOutcome> of = outcomes.stream().filter(o -> o.kind() == kind).toList();
        int correct = (int) of.stream().filter(o -> o.ask().status().equals("REFUSED")).count();
        return new Refusal(of.size(), correct, of.isEmpty() ? null : (double) correct / of.size());
    }

    private static Metrics metrics(List<ItemOutcome> calls) {
        List<ItemOutcome> answered = calls.stream().filter(o -> o.ask().status().equals("ANSWERED")).toList();
        int refused = (int) calls.stream().filter(o -> o.ask().status().equals("REFUSED")).count();
        int validationFailures = (int) calls.stream().filter(o -> "CITATION_VALIDATION_FAILED".equals(o.ask().refusalReason())).count();

        List<JudgeVerdict> points = answered.stream().flatMap(o -> o.pointVerdicts().stream()).toList();
        List<JudgeVerdict> sentenceVerdicts = answered.stream().flatMap(o -> o.sentences().stream()).map(GenerationRun.SentenceOutcome::verdict).toList();
        long flagged = answered.stream().flatMap(o -> o.sentences().stream()).filter(GenerationRun.SentenceOutcome::flagged).count();
        int supportedPoints = count(points, JudgeVerdict.SUPPORTED);
        int supportedSentences = count(sentenceVerdicts, JudgeVerdict.SUPPORTED);

        List<Integer> tokens = answered.stream().filter(o -> o.ask().promptTokens() != null && o.ask().completionTokens() != null)
                .map(o -> o.ask().promptTokens() + o.ask().completionTokens()).toList();
        List<Long> latencies = answered.stream().map(o -> o.ask().generationMs()).collect(Collectors.toList());

        int askErrors = (int) calls.stream().filter(o -> o.ask().status().equals("ERROR")).count();
        int decided = calls.size() - askErrors;
        List<GenerationRun.SentenceOutcome> sentences = answered.stream().flatMap(o -> o.sentences().stream()).toList();
        var overlap = new OverlapTable(overlapCount(sentences, true, JudgeVerdict.SUPPORTED), overlapCount(sentences, true, JudgeVerdict.UNSUPPORTED),
                overlapCount(sentences, false, JudgeVerdict.SUPPORTED), overlapCount(sentences, false, JudgeVerdict.UNSUPPORTED));
        return new Metrics(calls.size(), askErrors, overlap, answered.size(), decided == 0 ? null : (double) refused / decided,
                points.size(), supportedPoints, count(points, JudgeVerdict.ERROR),
                points.isEmpty() ? null : (double) supportedPoints / points.size(),
                sentenceVerdicts.size(), supportedSentences, count(sentenceVerdicts, JudgeVerdict.ERROR),
                sentenceVerdicts.isEmpty() ? null : (double) supportedSentences / sentenceVerdicts.size(),
                sentenceVerdicts.isEmpty() ? null : (double) flagged / sentenceVerdicts.size(), validationFailures,
                tokens.isEmpty() ? null : tokens.stream().mapToInt(Integer::intValue).average().orElse(0),
                latencies.isEmpty() ? null : ReportWriter.percentile(latencies, 50),
                latencies.isEmpty() ? null : ReportWriter.percentile(latencies, 95));
    }

    private static int overlapCount(List<GenerationRun.SentenceOutcome> sentences, boolean flagged, JudgeVerdict verdict) {
        return (int) sentences.stream().filter(x -> x.flagged() == flagged && x.verdict() == verdict).count();
    }

    private static int count(List<JudgeVerdict> verdicts, JudgeVerdict v) {
        return (int) verdicts.stream().filter(x -> x == v).count();
    }
}
