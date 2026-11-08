package rag.core.model;

import java.util.Locale;

public enum SourceType {
    REGULATORY,
    INTERNAL,
    ADVERSARIAL;

    public static SourceType parse(String value) {
        for (SourceType t : values()) {
            if (t.wire().equals(value)) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown sourceType: " + value);
    }

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
