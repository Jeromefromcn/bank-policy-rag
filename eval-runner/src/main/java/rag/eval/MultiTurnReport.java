package rag.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import rag.core.eval.ItemScores;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes {@code report.md} / {@code report.json} (item ids, variants and scores only, safe to quote in evidence) and
 * {@code rewrites.jsonl} (follow-ups and rewritten queries, which may quote earlier answers; stays in the gitignored
 * run directory). A failed conversation scores 0 for {@code rewrite} and is listed under failures.
 */
public final class MultiTurnReport {

    static final List<String> VARIANTS = List.of("raw", "rewrite", "standalone");
    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private static final JsonMapper LINE = JsonMapper.builder().build();
    private static final ItemScores ZERO = new ItemScores(0, 0, 0, 0, null);

    public record Aggregate(String variant, int n, double recall5, double mrr) {}

    private MultiTurnReport() {}

    public static void write(Path dir, RunMetadata meta, List<MultiTurnRun.Outcome> outcomes) {
        Map<String, List<Aggregate>> bySplit = new LinkedHashMap<>();
        for (String split : ReportWriter.SPLITS) {
            List<MultiTurnRun.Outcome> in = outcomes.stream().filter(o -> o.item().split().equals(split)).toList();
            if (!in.isEmpty()) {
                bySplit.put(split, aggregate(in));
            }
        }
        List<String> failures = outcomes.stream().filter(o -> o.error() != null).map(o -> o.item().id()).toList();
        long rewriteTotalMs = outcomes.stream().mapToLong(MultiTurnRun.Outcome::rewriteMs).sum();

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("metadata", meta);
        json.put("aggregates", bySplit);
        json.put("failures", failures);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var o : outcomes) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", o.item().id());
            row.put("split", o.item().split());
            row.put("raw", o.raw());
            row.put("rewrite", o.rewrite());
            row.put("standalone", o.standalone());
            row.put("rewriteMs", o.rewriteMs());
            row.put("promptTokens", o.promptTokens());
            row.put("completionTokens", o.completionTokens());
            row.put("failed", o.error() != null);
            rows.add(row);
        }
        json.put("items", rows);

        StringBuilder jsonl = new StringBuilder();
        for (var o : outcomes) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("id", o.item().id());
            line.put("followUp", o.item().followUp());
            line.put("rewrittenQuery", o.rewrittenQuery());
            line.put("error", o.error());
            jsonl.append(LINE.writeValueAsString(line)).append('\n');
        }
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("report.json"), JSON.writeValueAsString(json));
            Files.writeString(dir.resolve("report.md"), markdown(meta, bySplit, outcomes, failures, rewriteTotalMs));
            Files.writeString(dir.resolve("rewrites.jsonl"), jsonl.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<Aggregate> aggregate(List<MultiTurnRun.Outcome> in) {
        List<Aggregate> out = new ArrayList<>();
        out.add(agg("raw", in, MultiTurnRun.Outcome::raw));
        out.add(agg("rewrite", in, MultiTurnRun.Outcome::rewrite));
        out.add(agg("standalone", in, MultiTurnRun.Outcome::standalone));
        return out;
    }

    private static Aggregate agg(String variant, List<MultiTurnRun.Outcome> in, Function<MultiTurnRun.Outcome, ItemScores> f) {
        List<ItemScores> s = in.stream().map(f).map(x -> x == null ? ZERO : x).toList();
        return new Aggregate(variant, s.size(), s.stream().mapToDouble(ItemScores::recall5).average().orElse(0),
                s.stream().mapToDouble(ItemScores::reciprocalRank).average().orElse(0));
    }

    private static String markdown(RunMetadata meta, Map<String, List<Aggregate>> bySplit, List<MultiTurnRun.Outcome> outcomes,
            List<String> failures, long rewriteTotalMs) {
        StringBuilder md = new StringBuilder("# Multi-turn eval report\n\n| Key | Value |\n|---|---|\n");
        md.append("| timestamp | ").append(meta.timestamp()).append(" |\n");
        md.append("| git commit | ").append(meta.gitCommit()).append(meta.gitDirty() ? " (dirty)" : "").append(" |\n");
        md.append("| machine | ").append(meta.arch()).append(", ").append(meta.cpus()).append(" CPUs |\n");
        md.append("| base URL | ").append(meta.baseUrl()).append(" |\n");
        meta.info().forEach((k, v) -> md.append("| ").append(k).append(" | ").append(v).append(" |\n"));
        md.append("| mode | ").append(meta.mode()).append(" |\n| split | ").append(meta.split()).append(" |\n");
        md.append("| items run | ").append(outcomes.size()).append(" |\n");

        md.append("\n## Follow-up retrieval\n");
        bySplit.forEach((split, aggs) -> {
            md.append("\n### ").append(split).append("\n\n| Variant | n | Recall@5 | MRR |\n|---|---|---|---|\n");
            for (Aggregate a : aggs) {
                md.append(String.format(Locale.ROOT, "| %s | %d | %.3f | %.3f |%n", a.variant(), a.n(), a.recall5(), a.mrr()));
            }
        });
        md.append("\nFailures: ").append(failures.isEmpty() ? "none" : String.join(", ", failures)).append('\n');
        md.append("Rewrite latency, sum over items: ").append(rewriteTotalMs).append(" ms\n");

        md.append("\n## Items\n\n| Id | Split | raw R@5 / RR | rewrite R@5 / RR | standalone R@5 / RR | rewrite ms | tokens (prompt/completion) |\n");
        md.append("|---|---|---|---|---|---|---|\n");
        for (var o : outcomes) {
            md.append("| ").append(o.item().id()).append(" | ").append(o.item().split()).append(" | ").append(cell(o.raw()))
                    .append(" | ").append(o.rewrite() == null ? "failed" : cell(o.rewrite())).append(" | ").append(cell(o.standalone()))
                    .append(" | ").append(o.rewriteMs()).append(" | ").append(o.promptTokens()).append('/').append(o.completionTokens())
                    .append(" |\n");
        }
        return md.toString();
    }

    private static String cell(ItemScores s) {
        return String.format(Locale.ROOT, "%.3f / %.3f", s.recall5(), s.reciprocalRank());
    }
}
