package rag.eval;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;

/** Judge–human agreement on point verdicts: raw agreement and Cohen's kappa (spec 03 §8). */
public final class Agreement {

    public record LabeledPoint(String itemId, int pointIndex, boolean human, boolean judge) {}

    /** @param kappa null when undefined (both raters constant, so chance agreement is 1) */
    public record AgreementResult(int n, int agree, double agreement, Double kappa) {}

    private Agreement() {}

    public static AgreementResult compute(List<LabeledPoint> points) {
        int n = points.size();
        int agree = (int) points.stream().filter(p -> p.human() == p.judge()).count();
        double po = n == 0 ? 0 : (double) agree / n;
        double humanYes = n == 0 ? 0 : (double) points.stream().filter(LabeledPoint::human).count() / n;
        double judgeYes = n == 0 ? 0 : (double) points.stream().filter(LabeledPoint::judge).count() / n;
        double pe = humanYes * judgeYes + (1 - humanYes) * (1 - judgeYes);
        Double kappa = n == 0 || pe >= 1.0 ? null : (po - pe) / (1 - pe);
        return new AgreementResult(n, agree, po, kappa);
    }

    /** Reads the author's labels and joins them with the judge's verdicts from the run's {@code answers.json}. */
    public static List<LabeledPoint> load(Path labelsFile, Path runDir, int required) {
        if (!Files.exists(labelsFile)) {
            throw new IllegalArgumentException("labels file not found: " + labelsFile + " (the author labels the sampled points first)");
        }
        List<?> entries;
        try (Reader in = Files.newBufferedReader(labelsFile)) {
            Object loaded = new Yaml().load(in);
            entries = loaded instanceof List<?> l ? l : List.of();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        if (entries.size() < required) {
            throw new IllegalArgumentException(labelsFile + " has " + entries.size() + " labels; " + required + " are required");
        }
        JsonNode items = LabelSheet.read(runDir).path("items");
        List<LabeledPoint> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object entry : entries) {
            Map<?, ?> m = (Map<?, ?>) entry;
            String itemId = String.valueOf(m.get("itemId"));
            int index = ((Number) m.get("pointIndex")).intValue();
            if (!seen.add(itemId + "/" + index)) {
                throw new IllegalArgumentException("duplicate label for " + itemId + " point " + index);
            }
            boolean human = parseVerdict(m.get("humanVerdict"), itemId);
            out.add(new LabeledPoint(itemId, index, human, judgeVerdict(items, itemId, index)));
        }
        return out;
    }

    /** YAML 1.1 reads a bare yes/no as a boolean, so both forms are accepted. */
    private static boolean parseVerdict(Object raw, String itemId) {
        if (raw instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(raw).strip().toLowerCase(java.util.Locale.ROOT);
        return switch (s) {
            case "yes" -> true;
            case "no" -> false;
            default -> throw new IllegalArgumentException("humanVerdict for " + itemId + " must be yes or no, got: " + raw);
        };
    }

    private static boolean judgeVerdict(JsonNode items, String itemId, int index) {
        for (JsonNode item : items) {
            if (item.get("id").asString().equals(itemId) && item.path("kind").asString("").equals("ANSWERABLE")) {
                String v = item.path("pointVerdicts").path(index).asString("");
                if (v.equals("SUPPORTED")) {
                    return true;
                }
                if (v.equals("UNSUPPORTED")) {
                    return false;
                }
                throw new IllegalArgumentException("no usable judge verdict for " + itemId + " point " + index);
            }
        }
        throw new IllegalArgumentException("label names a point that is not in the run: " + itemId + " point " + index);
    }
}
