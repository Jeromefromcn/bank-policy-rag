package rag.core.eval;

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
import rag.core.model.Clearance;

/** Loads and validates {@code eval/golden-set.yaml}. Every error names the item id. */
public final class GoldenSet {

    public static final Set<String> CATEGORIES = Set.of("factual", "exact-term", "multi-hop", "unanswerable", "restricted");
    public static final Set<String> SPLITS = Set.of("dev", "test");

    private GoldenSet() {}

    @SuppressWarnings("unchecked")
    public static List<GoldenItem> load(Path file, Set<String> knownDocIds) {
        List<Map<String, Object>> raw;
        try {
            raw = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<GoldenItem> items = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> m : raw) {
            String id = String.valueOf(m.get("id"));
            GoldenItem item = parse(id, m);
            validate(item, knownDocIds);
            if (!ids.add(id)) {
                throw new IllegalArgumentException("duplicate golden item id " + id);
            }
            items.add(item);
        }
        return items;
    }

    @SuppressWarnings("unchecked")
    private static GoldenItem parse(String id, Map<String, Object> m) {
        List<ExpectedSource> sources = new ArrayList<>();
        for (Map<String, Object> s : (List<Map<String, Object>>) m.getOrDefault("expectedSources", List.of())) {
            sources.add(new ExpectedSource(require(id, s, "docId"), require(id, s, "section")));
        }
        List<String> points = ((List<Object>) m.getOrDefault("expectedPoints", List.of())).stream().map(String::valueOf).toList();
        Clearance clearance;
        try {
            clearance = Clearance.parse(require(id, m, "minClearance"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(id + ": " + e.getMessage(), e);
        }
        return new GoldenItem(id, require(id, m, "question"), require(id, m, "category"), sources, points, clearance,
                require(id, m, "split"));
    }

    private static void validate(GoldenItem item, Set<String> knownDocIds) {
        if (!CATEGORIES.contains(item.category())) {
            throw new IllegalArgumentException(item.id() + ": unknown category " + item.category());
        }
        if (!SPLITS.contains(item.split())) {
            throw new IllegalArgumentException(item.id() + ": unknown split " + item.split());
        }
        boolean unanswerable = item.category().equals("unanswerable");
        if (unanswerable != item.expectedSources().isEmpty()) {
            throw new IllegalArgumentException(item.id() + ": expectedSources must be empty exactly for unanswerable items");
        }
        for (ExpectedSource s : item.expectedSources()) {
            if (!knownDocIds.contains(s.docId())) {
                throw new IllegalArgumentException(item.id() + ": unknown docId " + s.docId());
            }
        }
    }

    private static String require(String id, Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new IllegalArgumentException(id + ": missing " + key);
        }
        return v.toString();
    }
}
