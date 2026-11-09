package rag.core.cite;

import java.util.List;

/**
 * Outcome of validating one answer (spec 03 §5).
 *
 * @param rangeOk    every {@code [n]} refers to an existing source
 * @param coverageOk every paragraph carries at least one citation, and the answer has at least one
 * @param citedRefs  distinct source numbers in order of first appearance
 * @param sentences  one entry per cited sentence, with its lexical overlap against the sources it cites
 */
public record CitationReport(boolean rangeOk, boolean coverageOk, List<Integer> citedRefs, List<SentenceCheck> sentences) {

    public CitationReport {
        citedRefs = List.copyOf(citedRefs);
        sentences = List.copyOf(sentences);
    }

    /** Hard failures refuse the answer; overlap flags never do. */
    public boolean hardFail() {
        return !rangeOk || !coverageOk;
    }

    public record SentenceCheck(String text, List<Integer> refs, double overlap, boolean flagged) {
        public SentenceCheck {
            refs = List.copyOf(refs);
        }
    }
}
