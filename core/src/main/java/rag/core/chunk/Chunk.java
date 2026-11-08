package rag.core.chunk;

import java.util.List;
import rag.core.model.SectionPath;

/**
 * A retrieval unit. {@code content} is exactly {@code text[start, end)} of its document.
 * {@code sectionPath} is the section in effect at {@code start}; {@code coveredSections} lists the
 * leaf numbers of every section in effect anywhere in the range, in document order (spec 01 §3.4).
 */
public record Chunk(
        String chunkId,
        String docId,
        String sectionId,
        int ordinal,
        String content,
        String contentHash,
        int tokenCount,
        int start,
        int end,
        List<Integer> pages,
        SectionPath sectionPath,
        List<String> coveredSections) {

    public Chunk {
        pages = List.copyOf(pages);
        coveredSections = List.copyOf(coveredSections);
    }
}
