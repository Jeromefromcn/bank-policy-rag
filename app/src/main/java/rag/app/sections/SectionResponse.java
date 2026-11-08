package rag.app.sections;

import java.util.List;

/** {@code GET /v1/sections/{sectionId}}: a depth-1 parent with its full text (ROADMAP §3.4). */
public record SectionResponse(
        String sectionId,
        String docId,
        String docTitle,
        List<String> sectionPath,
        List<Integer> pages,
        String classification,
        String content) {}
