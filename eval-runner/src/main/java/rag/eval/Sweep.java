package rag.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import rag.core.eval.GoldenItem;
import rag.core.model.Clearance;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Threshold sweep for the refusal gate (spec 03 §3.2). Retrieval only: no tokens. Uses {@code dev} items only and the
 * same keys the generation run uses, so false-refusal numbers describe the conditions the gate will run under.
 */
public final class Sweep {

    public enum Kind { ANSWERABLE, BELOW_CLEARANCE, UNANSWERABLE }

    public record Observation(String itemId, String category, Kind kind, Double bestSimilarity) {}

    public record Row(double threshold, Double unanswerableRefusedRate, Double restrictedRefusedRate, Double falseRefusalRate,
            Map<String, Double> falseRefusalByCategory) {}

    public record Table(int nAnswerable, int nUnanswerable, int nBelowClearance, int noSignal, Map<String, Integer> nByCategory,
            List<Row> rows) {}

    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private Sweep() {}

    public static List<Observation> collect(List<GoldenItem> items, KeySet keys, java.net.URI base, String mode) {
        List<Observation> out = new ArrayList<>();
        for (GoldenItem item : items) {
            if (!item.split().equals("dev")) {
                continue;
            }
            switch (item.category()) {
                case "unanswerable" -> out.add(observe(item, Kind.UNANSWERABLE, base, keys.forClearance(Clearance.RESTRICTED), mode));
                case "restricted" -> {
                    out.add(observe(item, Kind.ANSWERABLE, base, keys.forClearance(item.minClearance()), mode));
                    out.add(observe(item, Kind.BELOW_CLEARANCE, base, keys.forClearance(KeySet.below(item.minClearance())), mode));
                }
                default -> out.add(observe(item, Kind.ANSWERABLE, base, keys.forClearance(item.minClearance()), mode));
            }
        }
        return out;
    }

    private static Observation observe(GoldenItem item, Kind kind, java.net.URI base, String key, String mode) {
        var res = new SearchClient(base, key, mode).search(item.question(), EvalRunner.TOP_K);
        return new Observation(item.id(), item.category(), kind, res.bestVectorSimilarity());
    }

    public static Table table(List<Observation> observations) {
        List<Observation> answerable = of(observations, Kind.ANSWERABLE);
        List<Observation> unanswerable = of(observations, Kind.UNANSWERABLE);
        List<Observation> below = of(observations, Kind.BELOW_CLEARANCE);
        Map<String, Integer> nByCategory = new TreeMap<>();
        answerable.forEach(o -> nByCategory.merge(o.category(), 1, Integer::sum));
        int noSignal = (int) observations.stream().filter(o -> o.bestSimilarity() == null).count();

        List<Row> rows = new ArrayList<>();
        for (int i = 0; i <= 100; i++) {
            double t = i / 100.0;
            Map<String, Double> byCategory = new TreeMap<>();
            for (String category : nByCategory.keySet()) {
                byCategory.put(category, rate(answerable.stream().filter(o -> o.category().equals(category)).toList(), t));
            }
            rows.add(new Row(t, rate(unanswerable, t), rate(below, t), rate(answerable, t), byCategory));
        }
        return new Table(answerable.size(), unanswerable.size(), below.size(), noSignal, nByCategory, rows);
    }

    private static List<Observation> of(List<Observation> all, Kind kind) {
        return all.stream().filter(o -> o.kind() == kind).toList();
    }

    /** Share refused at this threshold; null when there are no observations. A missing similarity is never refused. */
    private static Double rate(List<Observation> obs, double threshold) {
        if (obs.isEmpty()) {
            return null;
        }
        long refused = obs.stream().filter(o -> o.bestSimilarity() != null && o.bestSimilarity() < threshold).count();
        return (double) refused / obs.size();
    }

    public static void write(Path dir, RunMetadata meta, Table table) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("metadata", meta);
        json.put("table", table);
        StringBuilder md = new StringBuilder("# Refusal threshold sweep (dev)\n\n");
        md.append("- Commit: ").append(meta.gitCommit()).append(" (dirty: ").append(meta.gitDirty()).append(")\n");
        md.append("- Index: ").append(meta.info()).append("\n- Mode: ").append(meta.mode()).append("\n");
        md.append("- n answerable ").append(table.nAnswerable()).append(", unanswerable ").append(table.nUnanswerable())
                .append(", restricted below clearance ").append(table.nBelowClearance()).append(", no similarity signal ")
                .append(table.noSignal()).append("\n- Answerable by category: ").append(table.nByCategory()).append("\n\n");
        md.append("| threshold | unanswerable refused | restricted refused | false refusal | false refusal by category |\n|---|---|---|---|---|\n");
        for (Row r : table.rows()) {
            md.append(String.format("| %.2f | %s | %s | %s | %s |%n", r.threshold(), fmt(r.unanswerableRefusedRate()),
                    fmt(r.restrictedRefusedRate()), fmt(r.falseRefusalRate()), r.falseRefusalByCategory()));
        }
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("sweep.json"), JSON.writeValueAsString(json));
            Files.writeString(dir.resolve("sweep.md"), md.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String fmt(Double d) {
        return d == null ? "n/a" : String.format("%.3f", d);
    }
}
