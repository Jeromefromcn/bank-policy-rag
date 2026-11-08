package rag.app.es;

/** Index and alias names; every name starts with the pool prefix ({@code rag} / {@code rag_dev}). */
public final class IndexNames {

    private IndexNames() {}

    public static String chunksAlias(String prefix) {
        return prefix + "-chunks";
    }

    /** e.g. {@code rag_dev-chunks-nomic768-fixed512-v1} from chunker {@code fixed-512-64-cl100k}. */
    public static String chunksIndex(String prefix, String model, int dims, String chunkerVersion, int version) {
        String[] parts = chunkerVersion.split("-");
        String chunker = parts.length >= 2 ? parts[0] + parts[1] : chunkerVersion;
        return prefix + "-chunks-" + model + dims + "-" + chunker + "-v" + version;
    }
}
