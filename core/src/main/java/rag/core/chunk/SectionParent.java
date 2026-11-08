package rag.core.chunk;

import java.util.List;
import rag.core.model.SectionPath;

/**
 * The parent of structural chunks: one depth-1 section with its full text (spec 02 §3.3).
 * {@code path} is empty for a document without section markers.
 */
public record SectionParent(String sectionId, String docId, SectionPath path, String content, List<Integer> pages) {

    public SectionParent {
        pages = List.copyOf(pages);
    }
}
