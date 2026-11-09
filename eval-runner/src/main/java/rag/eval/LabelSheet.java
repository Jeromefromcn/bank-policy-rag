package rag.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;

/**
 * Samples point verdicts for blind human labelling (spec 03 §8). Strata are (category, judge verdict); sampling
 * round-robins over strata in a seeded order so both verdicts and several categories appear. The sheet never shows the
 * judge's verdict. It holds answers and source text, so it is gitignored.
 */
public final class LabelSheet {

    public record SampledPoint(String itemId, int pointIndex, String category, boolean judgeSupported) {}

    private LabelSheet() {}

    public static List<SampledPoint> export(Path runDir, long seed, int n, Path sheet) {
        JsonNode items = read(runDir).path("items");
        Map<String, List<SampledPoint>> strata = new TreeMap<>();
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode item : items) {
            if (!item.path("kind").asString("").equals("ANSWERABLE") || !item.path("ask").path("status").asString("").equals("ANSWERED")) {
                continue;
            }
            byId.put(item.get("id").asString(), item);
            JsonNode verdicts = item.path("pointVerdicts");
            for (int i = 0; i < verdicts.size(); i++) {
                String v = verdicts.get(i).asString();
                if (v.equals("ERROR")) {
                    continue;
                }
                var point = new SampledPoint(item.get("id").asString(), i, item.path("category").asString(""), v.equals("SUPPORTED"));
                strata.computeIfAbsent(point.category() + "/" + point.judgeSupported(), k -> new ArrayList<>()).add(point);
            }
        }
        Random random = new Random(seed);
        List<List<SampledPoint>> queues = new ArrayList<>();
        for (List<SampledPoint> stratum : strata.values()) {
            Collections.shuffle(stratum, random);
            queues.add(stratum);
        }
        Collections.shuffle(queues, random);
        List<SampledPoint> sample = new ArrayList<>();
        for (int round = 0; sample.size() < n; round++) {
            boolean any = false;
            for (List<SampledPoint> q : queues) {
                if (round < q.size() && sample.size() < n) {
                    sample.add(q.get(round));
                    any = true;
                }
            }
            if (!any) {
                break;
            }
        }
        sample.sort(Comparator.comparing(SampledPoint::itemId).thenComparingInt(SampledPoint::pointIndex));
        write(sheet, sample, byId);
        return sample;
    }

    private static void write(Path sheet, List<SampledPoint> sample, Map<String, JsonNode> byId) {
        StringBuilder md = new StringBuilder("# Human label sheet (blind)\n\n");
        md.append("Do not commit this file: it contains answers and source text.\n\n");
        md.append("For each point below, decide: is the point stated in the answer **and** supported by at least one cited source?\n");
        md.append("Record your verdicts in `eval/human-labels.yaml` as `- {itemId: g001, pointIndex: 0, humanVerdict: yes}` (`yes` or `no`).\n\n");
        int k = 1;
        for (SampledPoint p : sample) {
            JsonNode item = byId.get(p.itemId());
            md.append("---\n\n## ").append(k++).append(". ").append(p.itemId()).append(" · point ").append(p.pointIndex()).append("\n\n");
            md.append("**Question:** ").append(item.path("question").asString("")).append("\n\n");
            md.append("**Point:** ").append(item.path("points").path(p.pointIndex()).asString("")).append("\n\n");
            md.append("**Answer:**\n\n> ").append(item.path("ask").path("answer").asString("").replace("\n", "\n> ")).append("\n\n");
            md.append("**Sources:**\n\n");
            int ref = 1;
            for (JsonNode source : item.path("sources")) {
                md.append("[").append(ref++).append("] ").append(source.asString().replace("\n", " ")).append("\n\n");
            }
        }
        try {
            Path parent = sheet.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(sheet, md.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static JsonNode read(Path runDir) {
        try {
            return ApiHttp.JSON.readTree(Files.readString(runDir.resolve("answers.json")));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + runDir.resolve("answers.json") + ": " + e.getMessage(), e);
        }
    }
}
