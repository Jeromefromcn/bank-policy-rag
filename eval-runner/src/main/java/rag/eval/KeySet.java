package rag.eval;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import rag.core.eval.GoldenItem;
import rag.core.model.Clearance;

/** API keys by clearance, from the environment (spec 03 §7). Only the restricted key is mandatory. */
public final class KeySet {

    private static final Map<Clearance, String> VARIABLES = Map.of(
            Clearance.RESTRICTED, "RAG_KEY_DEV", Clearance.INTERNAL, "RAG_KEY_INTERNAL", Clearance.PUBLIC, "RAG_KEY_PUBLIC");

    private final Map<Clearance, String> keys;

    private KeySet(Map<Clearance, String> keys) {
        this.keys = keys;
    }

    public static KeySet fromEnv(Map<String, String> env) {
        Map<Clearance, String> keys = new EnumMap<>(Clearance.class);
        VARIABLES.forEach((clearance, variable) -> {
            String value = env.get(variable);
            if (value != null && !value.isBlank()) {
                keys.put(clearance, value);
            }
        });
        if (!keys.containsKey(Clearance.RESTRICTED)) {
            throw new IllegalStateException("environment variable RAG_KEY_DEV is required");
        }
        return new KeySet(keys);
    }

    public String forClearance(Clearance clearance) {
        String key = keys.get(clearance);
        if (key == null) {
            throw new IllegalStateException("environment variable " + VARIABLES.get(clearance) + " is required for " + clearance.wire() + " callers");
        }
        return key;
    }

    /** Fails once, naming every missing variable, so a run never starts and then dies half-way through. */
    public void require(java.util.Collection<Clearance> needed) {
        List<String> missing = needed.stream().filter(c -> !keys.containsKey(c)).map(VARIABLES::get).sorted().toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("missing environment variable(s): " + String.join(", ", missing));
        }
    }

    /** Clearances the keys must cover to run these items in this split (the key at, and one below, a restricted item). */
    public static java.util.Set<Clearance> needed(List<GoldenItem> items, String split) {
        java.util.Set<Clearance> out = java.util.EnumSet.noneOf(Clearance.class);
        for (GoldenItem item : items) {
            if (!split.equals("all") && !item.split().equals(split)) {
                continue;
            }
            switch (item.category()) {
                case "unanswerable" -> out.add(Clearance.RESTRICTED);
                case "restricted" -> {
                    out.add(item.minClearance());
                    out.add(below(item.minClearance()));
                }
                default -> out.add(item.minClearance());
            }
        }
        return out;
    }

    /** The clearance one level below; there is none below {@code PUBLIC}. */
    public static Clearance below(Clearance clearance) {
        if (clearance == Clearance.PUBLIC) {
            throw new IllegalArgumentException("no clearance below public");
        }
        return Clearance.values()[clearance.ordinal() - 1];
    }
}
