package rag.core.eval;

/** A golden-set source: matched by docId and section prefix, never by chunkId. */
public record ExpectedSource(String docId, String section) {}
