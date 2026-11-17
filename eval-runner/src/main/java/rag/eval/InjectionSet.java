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
import rag.core.model.Clearance;

/** Loads and validates {@code eval/injection.yaml}; every error names the item id. */
public final class InjectionSet {

    static final Set<String> STATUSES = Set.of("ANSWERED", "REFUSED");

    private InjectionSet() {}

    @SuppressWarnings("unchecked")
    public static List<InjectionItem> load(Path file, Set<String> knownDocIds) {
        List<Map<String, Object>> raw;
        try {
            raw = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<InjectionItem> items = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> m : raw == null ? List.<Map<String, Object>>of() : raw) {
            String id = String.valueOf(m.get("id"));
            if (!ids.add(id)) {
                throw new IllegalArgumentException("duplicate injection item id " + id);
            }
            items.add(parse(id, m, knownDocIds));
        }
        return items;
    }

    @SuppressWarnings("unchecked")
    private static InjectionItem parse(String id, Map<String, Object> m, Set<String> knownDocIds) {
        String expectedStatus = require(id, m, "expectedStatus");
        if (!STATUSES.contains(expectedStatus)) {
            throw new IllegalArgumentException(id + ": unknown expectedStatus " + expectedStatus);
        }
        Clearance clearance;
        try {
            clearance = Clearance.parse(require(id, m, "clearance"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(id + ": unknown clearance: " + e.getMessage(), e);
        }
        List<String> canaries = strings(m.get("mustNotContain"));
        List<String> forbidden = strings(m.get("mustNotCiteDocIds"));
        for (String docId : forbidden) {
            if (!knownDocIds.contains(docId)) {
                throw new IllegalArgumentException(id + ": unknown docId " + docId + " in mustNotCiteDocIds");
            }
        }
        return new InjectionItem(id, require(id, m, "question"), clearance, expectedStatus, canaries, forbidden);
    }

    private static List<String> strings(Object value) {
        if (value == null) {
            return List.of();
        }
        return ((List<Object>) value).stream().map(String::valueOf).toList();
    }

    private static String require(String id, Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new IllegalArgumentException(id + ": missing " + key);
        }
        return v.toString();
    }
}
