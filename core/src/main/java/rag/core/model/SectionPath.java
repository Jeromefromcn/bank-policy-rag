package rag.core.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A section's number chain, e.g. {@code ["3", "3.2", "3.2.1"]}. Prefix tests are component-wise,
 * so {@code 3.2} prefixes {@code 3.2.1} but not {@code 3.20}. The empty path matches nothing.
 */
public record SectionPath(List<String> components) implements Comparable<SectionPath> {

    private static final SectionPath EMPTY = new SectionPath(List.of());

    public SectionPath {
        components = List.copyOf(components);
    }

    public static SectionPath empty() {
        return EMPTY;
    }

    /** Builds the chain from a dotted number: {@code "3.2.1"} → {@code ["3", "3.2", "3.2.1"]}. */
    public static SectionPath parse(String dotted) {
        if (dotted == null || dotted.isBlank()) {
            return EMPTY;
        }
        String[] parts = dotted.strip().split("\\.");
        List<String> chain = new ArrayList<>(parts.length);
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty() || !part.chars().allMatch(Character::isDigit)) {
                throw new IllegalArgumentException("not a section number: " + dotted);
            }
            if (!sb.isEmpty()) {
                sb.append('.');
            }
            sb.append(part);
            chain.add(sb.toString());
        }
        return new SectionPath(chain);
    }

    public boolean isEmpty() {
        return components.isEmpty();
    }

    /** The full dotted number, or {@code ""} for the empty path. */
    public String leaf() {
        return isEmpty() ? "" : components.getLast();
    }

    public boolean isPrefixOf(SectionPath other) {
        if (isEmpty() || components.size() > other.components.size()) {
            return false;
        }
        return other.components.subList(0, components.size()).equals(components);
    }

    @Override
    public int compareTo(SectionPath other) {
        int[] a = numbers();
        int[] b = other.numbers();
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return Integer.compare(a.length, b.length);
    }

    private int[] numbers() {
        if (isEmpty()) {
            return new int[0];
        }
        String[] parts = leaf().split("\\.");
        int[] n = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            n[i] = Integer.parseInt(parts[i]);
        }
        return n;
    }
}
