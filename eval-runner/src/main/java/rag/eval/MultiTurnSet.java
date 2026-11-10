package rag.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import rag.core.eval.ExpectedSource;
import rag.core.eval.GoldenItem;
import rag.core.eval.GoldenSet;
import rag.core.model.Clearance;

/** Loads and validates {@code eval/multi-turn.yaml} against the golden set. Every error names the item id. */
public final class MultiTurnSet {

    private MultiTurnSet() {}

    @SuppressWarnings("unchecked")
    public static List<MultiTurnItem> load(Path file, Map<String, GoldenItem> golden) {
        List<Map<String, Object>> raw;
        try {
            raw = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<MultiTurnItem> items = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> m : raw == null ? List.<Map<String, Object>>of() : raw) {
            String id = String.valueOf(m.get("id"));
            MultiTurnItem item = parse(id, m, golden);
            if (!ids.add(id)) {
                throw new IllegalArgumentException("duplicate multi-turn item id " + id);
            }
            items.add(item);
        }
        return items;
    }

    @SuppressWarnings("unchecked")
    private static MultiTurnItem parse(String id, Map<String, Object> m, Map<String, GoldenItem> golden) {
        String split = require(id, m, "split");
        if (!GoldenSet.SPLITS.contains(split)) {
            throw new IllegalArgumentException(id + ": unknown split " + split);
        }
        List<String> turns = ((List<Object>) m.getOrDefault("turns", List.of())).stream().map(String::valueOf).toList();
        if (turns.isEmpty()) {
            throw new IllegalArgumentException(id + ": turns must name at least one golden item");
        }
        for (String t : turns) {
            if (!golden.containsKey(t)) {
                throw new IllegalArgumentException(id + ": unknown golden id " + t + " in turns");
            }
        }
        String followUp = require(id, m, "followUp");
        String standalone = require(id, m, "standaloneGoldenId");
        if (!golden.containsKey(standalone)) {
            throw new IllegalArgumentException(id + ": unknown golden id " + standalone + " in standaloneGoldenId");
        }
        List<ExpectedSource> sources = new ArrayList<>();
        for (Map<String, Object> s : (List<Map<String, Object>>) m.getOrDefault("expectedSources", List.of())) {
            sources.add(new ExpectedSource(require(id, s, "docId"), require(id, s, "section")));
        }
        if (sources.isEmpty()) {
            sources.addAll(golden.get(standalone).expectedSources());
        }
        if (sources.isEmpty()) {
            throw new IllegalArgumentException(id + ": no expectedSources (the standalone item has none)");
        }
        Clearance clearance;
        try {
            clearance = Clearance.parse(require(id, m, "minClearance"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(id + ": " + e.getMessage(), e);
        }
        return new MultiTurnItem(id, split, turns, followUp, standalone, sources, clearance);
    }

    private static String require(String id, Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new IllegalArgumentException(id + ": missing " + key);
        }
        return v.toString();
    }
}
