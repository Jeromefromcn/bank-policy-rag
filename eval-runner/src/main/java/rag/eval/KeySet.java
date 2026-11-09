package rag.eval;

import java.util.EnumMap;
import java.util.Map;
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

    /** The clearance one level below; there is none below {@code PUBLIC}. */
    public static Clearance below(Clearance clearance) {
        if (clearance == Clearance.PUBLIC) {
            throw new IllegalArgumentException("no clearance below public");
        }
        return Clearance.values()[clearance.ordinal() - 1];
    }
}
