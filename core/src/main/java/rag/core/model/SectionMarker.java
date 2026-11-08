package rag.core.model;

/** The point in {@link ParsedDocument#text()} where a numbered section begins. */
public record SectionMarker(int offset, SectionPath path) {}
