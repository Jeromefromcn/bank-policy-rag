package rag.app.embed;

import rag.app.RagProperties;

/** One place for the model's task prefixes, shared by Ollama and fixture embeddings. */
public final class EmbeddingPrefixes {

    private EmbeddingPrefixes() {}

    public static String query(RagProperties.ModelSpec spec, String text) {
        return spec.queryPrefix() + text;
    }

    public static String document(RagProperties.ModelSpec spec, String text) {
        return spec.documentPrefix() + text;
    }
}
