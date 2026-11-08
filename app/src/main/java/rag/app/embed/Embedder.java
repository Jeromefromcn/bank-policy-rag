package rag.app.embed;

import java.util.List;

/**
 * Text embedding with the model's task prefixes applied inside the implementation, never by
 * callers (spec 00 §5).
 */
public interface Embedder {

    /** e.g. {@code ollama/nomic-embed-text}; recorded on every chunk and in the index {@code _meta}. */
    String modelId();

    /** Short name used in physical index names, e.g. {@code nomic}. */
    String shortName();

    int dims();

    float[] embedQuery(String query);

    List<float[]> embedDocuments(List<String> texts);
}
