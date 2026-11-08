package rag.core.chunk;

import java.util.ArrayList;
import java.util.List;
import rag.core.ids.Hashes;
import rag.core.model.PageSpan;
import rag.core.model.ParsedDocument;
import rag.core.model.SectionMarker;
import rag.core.model.SectionPath;

/** Builds the depth-1 parents of a document (spec 02 §3.3). */
public final class SectionParents {

    private SectionParents() {}

    /** The depth-1 path of {@code path}: {@code 3.2.1 → 3}; the empty path stays empty. */
    public static SectionPath parentPath(SectionPath path) {
        return path.isEmpty() ? path : new SectionPath(List.of(path.components().getFirst()));
    }

    public static List<SectionParent> of(ParsedDocument doc) {
        String text = doc.text();
        List<SectionParent> parents = new ArrayList<>();
        if (doc.markers().isEmpty()) {
            if (!text.isBlank()) {
                parents.add(parent(doc, SectionPath.empty(), 0, text.length()));
            }
            return parents;
        }
        List<SectionMarker> markers = doc.markers();
        int i = 0;
        while (i < markers.size()) {
            SectionPath path = parentPath(markers.get(i).path());
            int j = i + 1;
            while (j < markers.size() && parentPath(markers.get(j).path()).equals(path)) {
                j++;
            }
            int end = j < markers.size() ? markers.get(j).offset() : text.length();
            parents.add(parent(doc, path, markers.get(i).offset(), end));
            i = j;
        }
        return parents;
    }

    private static SectionParent parent(ParsedDocument doc, SectionPath path, int start, int end) {
        String content = doc.text().substring(start, end).strip();
        List<Integer> pages = new ArrayList<>();
        for (PageSpan span : doc.pages()) {
            if (start < span.end() && end > span.start()) {
                pages.add(span.page());
            }
        }
        return new SectionParent(Hashes.sectionId(doc.docId(), path), doc.docId(), path, content, pages);
    }
}
