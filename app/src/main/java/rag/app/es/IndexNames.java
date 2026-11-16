package rag.app.es;

/** Index and alias names; every name starts with the pool prefix ({@code rag} / {@code rag_dev}). */
public final class IndexNames {

    private IndexNames() {}

    public static String chunksAlias(String prefix) {
        return prefix + "-chunks";
    }

    public static String sectionsAlias(String prefix) {
        return prefix + "-sections";
    }

    /** The append-only audit index, e.g. {@code audit-rag_dev-v1} (spec 05 §4.1). */
    public static String auditIndex(String prefix) {
        return "audit-" + prefix + "-v1";
    }

    /**
     * e.g. {@code rag_dev-chunks-nomic768-fixed512-v1} from chunker {@code fixed-512-64-cl100k}. A model name that
     * ends in a digit gets a dash before the dims ({@code bgem3-1024}) so the name stays readable. A chunker
     * version with extra segments (a non-default minimum) keeps them, so it never shares a name with the default.
     */
    public static String chunksIndex(String prefix, String model, int dims, String chunkerVersion, int version) {
        String sep = Character.isDigit(model.charAt(model.length() - 1)) ? "-" : "";
        return prefix + "-chunks-" + model + sep + dims + "-" + chunkerSegment(chunkerVersion) + "-v" + version;
    }

    /** e.g. {@code rag_dev-sections-struct400-v1}. */
    public static String sectionsIndex(String prefix, String chunkerVersion, int version) {
        return prefix + "-sections-" + chunkerSegment(chunkerVersion) + "-v" + version;
    }

    /** {@code fixed-512-64-cl100k → fixed512}; {@code structural-v1-400-cl100k → struct400}; extras appended. */
    private static String chunkerSegment(String chunkerVersion) {
        String[] parts = chunkerVersion.split("-");
        if (parts[0].equals("structural") && parts.length >= 3) {
            String extra = parts.length > 4 ? "-" + String.join("-", java.util.Arrays.copyOfRange(parts, 4, parts.length)) : "";
            return "struct" + parts[2] + extra;
        }
        return parts.length >= 2 ? parts[0] + parts[1] : chunkerVersion;
    }
}
