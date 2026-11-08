package rag.core.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Ordered classification levels: {@code PUBLIC < INTERNAL < RESTRICTED}. */
public enum Clearance {
    PUBLIC,
    INTERNAL,
    RESTRICTED;

    /** Parses the lower-case wire form used in front matter, config and Elasticsearch. */
    public static Clearance parse(String value) {
        for (Clearance c : values()) {
            if (c.wire().equals(value)) {
                return c;
            }
        }
        throw new IllegalArgumentException("unknown classification: " + value);
    }

    /** Levels a caller with this clearance may see: every level at or below it. */
    public Set<Clearance> visible() {
        return Arrays.stream(values()).filter(c -> c.ordinal() <= ordinal()).collect(Collectors.toUnmodifiableSet());
    }

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
