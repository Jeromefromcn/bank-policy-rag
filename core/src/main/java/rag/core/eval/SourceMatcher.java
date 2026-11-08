package rag.core.eval;

import rag.core.model.SectionPath;

/**
 * A hit matches an expected source if the docId is equal and the expected section is a
 * component-wise prefix of the hit's section path or of any covered section (spec 01 §3.4).
 */
public final class SourceMatcher {

    private SourceMatcher() {}

    public static boolean matches(ExpectedSource expected, RankedHit hit) {
        if (!expected.docId().equals(hit.docId())) {
            return false;
        }
        SectionPath want = SectionPath.parse(expected.section());
        if (want.isPrefixOf(new SectionPath(hit.sectionPath()))) {
            return true;
        }
        return hit.coveredSections().stream().anyMatch(c -> want.isPrefixOf(SectionPath.parse(c)));
    }
}
