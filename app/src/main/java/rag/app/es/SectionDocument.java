package rag.app.es;

import java.util.List;
import rag.core.chunk.SectionParent;
import rag.core.model.ParsedDocument;

/** The {@code sections} index document: a depth-1 parent with its full text (ROADMAP §4.1, spec 02 §3.3). Not embedded. */
public record SectionDocument(
        String sectionId,
        String docId,
        String docTitle,
        String docVersion,
        String sourceType,
        List<String> sectionPath,
        List<Integer> pages,
        String classification,
        String content,
        String corpusVersion) {

    public static SectionDocument of(SectionParent p, ParsedDocument d, String corpusVersion) {
        return new SectionDocument(p.sectionId(), p.docId(), d.title(), d.docVersion(), d.sourceType().wire(),
                p.path().components(), p.pages(), d.classification().wire(), p.content(), corpusVersion);
    }
}
