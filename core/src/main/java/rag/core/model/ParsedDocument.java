package rag.core.model;

import java.util.List;

/**
 * A source document as plain text plus the metadata chunking needs. {@code pages} is empty for
 * Markdown; {@code markers} are in offset order.
 */
public record ParsedDocument(
        String docId,
        String title,
        String docVersion,
        SourceType sourceType,
        Clearance classification,
        String text,
        List<PageSpan> pages,
        List<SectionMarker> markers) {

    public ParsedDocument {
        pages = List.copyOf(pages);
        markers = List.copyOf(markers);
    }
}
