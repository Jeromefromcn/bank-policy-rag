package rag.core.model;

/** A PDF page's character range in {@link ParsedDocument#text()}: {@code [start, end)}, page 1-based. */
public record PageSpan(int page, int start, int end) {}
