package rag.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import rag.eval.GenerationMetrics.Metrics;
import rag.eval.GenerationMetrics.Summary;
import rag.eval.GenerationRun.ItemOutcome;
import rag.eval.judge.JudgeVerdict;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes {@code report.json} / {@code report.md} (ids, statuses and counts only, safe to quote in evidence) and
 * {@code answers.json} (answers and per-sentence verdicts; stays in the gitignored run directory).
 */
public final class GenerationReport {

    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private GenerationReport() {}

    public static void write(Path dir, RunMetadata meta, List<ItemOutcome> outcomes, List<Summary> summaries) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("metadata", meta);
        report.put("summaries", summaries);
        report.put("items", outcomes.stream().map(GenerationReport::itemRow).toList());

        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("warning", "Contains answers and source-derived text. Do not commit.");
        answers.put("items", outcomes.stream().map(GenerationReport::answerRow).toList());
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("report.json"), JSON.writeValueAsString(report));
            Files.writeString(dir.resolve("report.md"), markdown(meta, summaries, outcomes));
            Files.writeString(dir.resolve("answers.json"), JSON.writeValueAsString(answers));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Object> itemRow(ItemOutcome o) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", o.item().id());
        row.put("category", o.item().category());
        row.put("split", o.item().split());
        row.put("kind", o.kind());
        row.put("status", o.ask().status());
        row.put("refusalReason", o.ask().status().equals("ERROR") ? "request failed" : o.ask().refusalReason());
        row.put("pointsSupported", count(o.pointVerdicts(), JudgeVerdict.SUPPORTED));
        row.put("pointsTotal", o.pointVerdicts().size());
        row.put("pointErrors", count(o.pointVerdicts(), JudgeVerdict.ERROR));
        row.put("citedSentences", o.sentences().size());
        row.put("flaggedSentences", o.sentences().stream().filter(GenerationRun.SentenceOutcome::flagged).count());
        return row;
    }

    private static Map<String, Object> answerRow(ItemOutcome o) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", o.item().id());
        row.put("split", o.item().split());
        row.put("category", o.item().category());
        row.put("question", o.item().question());
        row.put("kind", o.kind());
        row.put("points", o.item().expectedPoints());
        row.put("sources", o.sources());
        row.put("ask", o.ask());
        row.put("pointVerdicts", o.pointVerdicts());
        row.put("sentences", o.sentences());
        return row;
    }

    private static long count(List<JudgeVerdict> verdicts, JudgeVerdict v) {
        return verdicts.stream().filter(x -> x == v).count();
    }

    private static String markdown(RunMetadata meta, List<Summary> summaries, List<ItemOutcome> outcomes) {
        StringBuilder md = new StringBuilder("# Generation eval\n\n");
        md.append("- Commit: ").append(meta.gitCommit()).append(" (dirty: ").append(meta.gitDirty()).append(")\n");
        md.append("- Machine: ").append(meta.arch()).append(", ").append(meta.cpus()).append(" CPUs\n");
        md.append("- Mode: ").append(meta.mode()).append(", split: ").append(meta.split()).append("\n");
        md.append("- Versions: ").append(meta.info()).append("\n\n");
        for (Summary s : summaries) {
            md.append("## Split: ").append(s.split()).append("\n\n");
            md.append("| group | n calls | ask errors | answered | false refusal | faithfulness (supported/points) | point errors | citation support (supported/sentences) | sentence errors | soft-flag rate | validation failures | mean tokens | gen p50/p95 ms |\n");
            md.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
            s.byCategory().forEach((group, m) -> md.append(row(group, m)));
            md.append("\nOverlap flag vs judge (sentences; flagged = overlap below threshold):\n\n| group | flagged+supported | flagged+unsupported | unflagged+supported | unflagged+unsupported |\n|---|---|---|---|---|\n");
            s.byCategory().forEach((group, m) -> md.append(String.format("| %s | %d | %d | %d | %d |%n", group, m.overlapVsJudge().flaggedSupported(),
                    m.overlapVsJudge().flaggedUnsupported(), m.overlapVsJudge().unflaggedSupported(), m.overlapVsJudge().unflaggedUnsupported())));
            md.append("\n| refusal check | n | correctly refused | accuracy |\n|---|---|---|---|\n");
            md.append("| unanswerable | ").append(s.unanswerable().n()).append(" | ").append(s.unanswerable().correct()).append(" | ")
                    .append(fmt(s.unanswerable().accuracy())).append(" |\n");
            md.append("| restricted (below clearance) | ").append(s.restricted().n()).append(" | ").append(s.restricted().correct())
                    .append(" | ").append(fmt(s.restricted().accuracy())).append(" |\n\n");
        }
        long errors = outcomes.stream().filter(o -> o.ask().status().equals("ERROR")).count();
        md.append("Request errors: ").append(errors).append(errors > 0 ? " (excluded from false refusal rates; headline numbers are partial)" : "").append("\n\n");
        md.append("Small n: differences of a single item are not evidence. Tuning decisions use `dev` only.\n");
        return md.toString();
    }

    private static String row(String group, Metrics m) {
        return String.format("| %s | %d | %d | %d | %s | %s (%d/%d) | %d | %s (%d/%d) | %d | %s | %d | %s | %s |%n", group, m.nCalls(), m.askErrors(),
                m.nAnswered(), fmt(m.falseRefusalRate()), fmt(m.faithfulness()), m.supportedPoints(), m.points(), m.pointErrors(),
                fmt(m.citationSupport()), m.supportedSentences(), m.sentences(), m.sentenceErrors(), fmt(m.softFlagRate()),
                m.validationFailures(), m.meanTotalTokens() == null ? "n/a" : String.format("%.0f", m.meanTotalTokens()),
                m.genP50() == null ? "n/a" : m.genP50() + "/" + m.genP95());
    }

    private static String fmt(Double d) {
        return d == null ? "n/a" : String.format("%.3f", d);
    }
}
