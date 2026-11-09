package rag.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import rag.core.eval.ItemScores;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes {@code report.json} and {@code report.md}. The Markdown shows ids and ranks only: no
 * question text and never chunk text, which may be SPM text.
 */
public class ReportWriter {

    static final List<String> SPLITS = List.of("test", "dev");
    static final List<String> CATEGORIES = List.of("factual", "exact-term", "multi-hop", "restricted");

    public record Aggregate(String group, int n, double recall5, double recall10, double mrr, double ndcg10) {}

    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    public void write(Path dir, RunMetadata meta, List<ItemResult> items) {
        Map<String, List<Aggregate>> aggregates = new LinkedHashMap<>();
        for (String split : SPLITS) {
            List<ItemResult> inSplit = items.stream().filter(i -> i.item().split().equals(split)).toList();
            if (!inSplit.isEmpty()) {
                aggregates.put(split, aggregate(inSplit));
            }
        }
        List<Long> latencies = items.stream().map(ItemResult::latencyMs).toList();
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("metadata", meta);
        json.put("aggregates", aggregates);
        json.put("latencyMs", Map.of("p50", latencies.isEmpty() ? 0 : percentile(latencies, 50),
                "p95", latencies.isEmpty() ? 0 : percentile(latencies, 95)));
        json.put("latencyStages", latencyStages(items));
        json.put("items", items.stream().map(i -> Map.of("id", i.item().id(), "category", i.item().category(),
                "split", i.item().split(), "scores", i.scores(), "latencyMs", i.latencyMs())).toList());
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("report.json"), JSON.writeValueAsString(json));
            Files.writeString(dir.resolve("report.md"), markdown(meta, aggregates, latencyStages(items), items));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Percentiles over a set of latencies; {@code n} is how many values they cover. */
    public record Percentiles(int n, long p50, long p95) {}

    /** Every repetition of every item ({@code all}) and the first repetition only ({@code cold}). */
    public record StageLatency(Percentiles all, Percentiles cold) {}

    /**
     * Latency per stage (spec 04 §7.1): client total, server retrieval, server rerank and their sum. A server stage
     * appears only when every repetition reported it.
     */
    static Map<String, StageLatency> latencyStages(List<ItemResult> items) {
        Map<String, java.util.function.Function<ItemResult.Latency, Long>> stages = new LinkedHashMap<>();
        stages.put("total", ItemResult.Latency::totalMs);
        stages.put("retrieval", ItemResult.Latency::retrievalMs);
        stages.put("rerank", ItemResult.Latency::rerankMs);
        stages.put("retrievalPlusRerank", l -> l.retrievalMs() == null || l.rerankMs() == null ? null : l.retrievalMs() + l.rerankMs());
        Map<String, StageLatency> out = new LinkedHashMap<>();
        stages.forEach((name, f) -> {
            List<Long> all = items.stream().flatMap(i -> i.repetitions().stream()).map(f).toList();
            List<Long> cold = items.stream().map(i -> f.apply(i.repetitions().getFirst())).toList();
            if (!all.isEmpty() && all.stream().noneMatch(java.util.Objects::isNull)) {
                out.put(name, new StageLatency(percentiles(all), percentiles(cold)));
            }
        });
        return out;
    }

    private static Percentiles percentiles(List<Long> values) {
        return new Percentiles(values.size(), percentile(values, 50), percentile(values, 95));
    }

    static List<Aggregate> aggregate(List<ItemResult> items) {
        List<Aggregate> rows = new ArrayList<>();
        rows.add(row("all", items));
        for (String c : CATEGORIES) {
            List<ItemResult> inCategory = items.stream().filter(i -> i.item().category().equals(c)).toList();
            if (!inCategory.isEmpty()) {
                rows.add(row(c, inCategory));
            }
        }
        return rows;
    }

    private static Aggregate row(String group, List<ItemResult> items) {
        return new Aggregate(group, items.size(), mean(items, ItemScores::recall5), mean(items, ItemScores::recall10),
                mean(items, ItemScores::reciprocalRank), mean(items, ItemScores::ndcg10));
    }

    private static double mean(List<ItemResult> items, ToDoubleFunction<ItemScores> f) {
        return items.stream().mapToDouble(i -> f.applyAsDouble(i.scores())).average().orElse(0);
    }

    /** Nearest-rank percentile. */
    static long percentile(List<Long> values, int p) {
        List<Long> sorted = values.stream().sorted().toList();
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(0, rank - 1));
    }

    private static String markdown(RunMetadata meta, Map<String, List<Aggregate>> aggregates, Map<String, StageLatency> stages,
            List<ItemResult> items) {
        StringBuilder md = new StringBuilder("# Retrieval eval report\n\n");
        md.append("| Key | Value |\n|---|---|\n");
        row(md, "timestamp", meta.timestamp());
        row(md, "git commit", meta.gitCommit() + (meta.gitDirty() ? " (dirty)" : ""));
        row(md, "machine", meta.arch() + ", " + meta.cpus() + " CPUs");
        row(md, "base URL", meta.baseUrl());
        meta.info().forEach((k, v) -> row(md, k, v));
        row(md, "mode", meta.mode());
        row(md, "split", meta.split());
        row(md, "items run", Integer.toString(items.size()));
        row(md, "unanswerable skipped", Integer.toString(meta.skipped()));

        md.append("\n## Retrieval metrics\n");
        aggregates.forEach((split, rows) -> {
            md.append("\n### ").append(split).append("\n\n");
            md.append("| Group | n | Recall@5 | Recall@10 | MRR | nDCG@10 |\n|---|---|---|---|---|---|\n");
            for (Aggregate a : rows) {
                md.append("| ").append(a.group()).append(" | ").append(a.n()).append(" | ").append(f(a.recall5()))
                        .append(" | ").append(f(a.recall10())).append(" | ").append(f(a.mrr())).append(" | ")
                        .append(f(a.ndcg10())).append(" |\n");
            }
        });

        md.append("\n## Latency (ms; all repetitions, and the first repetition alone as cold)\n\n");
        md.append("| Stage | n | p50 | p95 | cold n | cold p50 | cold p95 |\n|---|---|---|---|---|---|---|\n");
        Map<String, String> labels = Map.of("total", "total (client, /v1/search)", "retrieval", "retrieval (server)",
                "rerank", "rerank (server)", "retrievalPlusRerank", "retrieval + rerank (server)");
        stages.forEach((name, st) -> md.append("| ").append(labels.get(name)).append(" | ").append(st.all().n()).append(" | ")
                .append(st.all().p50()).append(" | ").append(st.all().p95()).append(" | ").append(st.cold().n()).append(" | ")
                .append(st.cold().p50()).append(" | ").append(st.cold().p95()).append(" |\n"));

        md.append("\n## Items\n\n| Id | Category | Split | First match rank | Recall@5 |\n|---|---|---|---|---|\n");
        items.stream().sorted(Comparator.comparing(i -> i.item().id())).forEach(i -> md.append("| ").append(i.item().id())
                .append(" | ").append(i.item().category()).append(" | ").append(i.item().split()).append(" | ")
                .append(i.scores().firstMatchRank() == null ? "-" : i.scores().firstMatchRank().toString()).append(" | ")
                .append(f(i.scores().recall5())).append(" |\n"));
        return md.toString();
    }

    private static void row(StringBuilder md, String key, String value) {
        md.append("| ").append(key).append(" | ").append(value).append(" |\n");
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }
}
