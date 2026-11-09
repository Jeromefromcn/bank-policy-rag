package rag.core.cite;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the {@code [n]} citations of a generated answer against the numbered sources it was given
 * (spec 03 §5): range, per-paragraph coverage, and a soft lexical-overlap flag per cited sentence.
 */
public final class CitationValidator {

    private static final Pattern MARKER = Pattern.compile("\\[(\\d+(?:\\s*,\\s*\\d+)*)]");
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:[-*•]|\\d+[.)])\\s+.*");
    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.!?])\\s+(?=[A-Z\\[])|\\R");

    private final double minOverlap;

    public CitationValidator(double minOverlap) {
        this.minOverlap = minOverlap;
    }

    public CitationReport validate(String answer, List<String> sources) {
        Set<Integer> cited = new LinkedHashSet<>();
        boolean rangeOk = true;
        for (int ref : refsIn(answer)) {
            cited.add(ref);
            if (ref < 1 || ref > sources.size()) {
                rangeOk = false;
            }
        }

        boolean coverageOk = !cited.isEmpty();
        for (String paragraph : paragraphs(answer)) {
            if (refsIn(paragraph).isEmpty()) {
                coverageOk = false;
            }
        }

        List<CitationReport.SentenceCheck> sentences = new ArrayList<>();
        for (String raw : SENTENCE_BREAK.split(answer)) {
            List<Integer> refs = refsIn(raw);
            if (refs.isEmpty()) {
                continue;
            }
            String text = MARKER.matcher(raw).replaceAll("").strip();
            Set<String> sourceTokens = new HashSet<>();
            for (int ref : refs) {
                if (ref >= 1 && ref <= sources.size()) {
                    sourceTokens.addAll(Tokens.of(sources.get(ref - 1)));
                }
            }
            double overlap = overlap(Tokens.of(text), sourceTokens);
            sentences.add(new CitationReport.SentenceCheck(text, refs, overlap, overlap < minOverlap));
        }
        return new CitationReport(rangeOk, coverageOk, List.copyOf(cited), sentences);
    }

    private static double overlap(List<String> sentenceTokens, Set<String> sourceTokens) {
        if (sentenceTokens.isEmpty()) {
            return 1.0;
        }
        long found = sentenceTokens.stream().filter(sourceTokens::contains).count();
        return (double) found / sentenceTokens.size();
    }

    private static List<Integer> refsIn(String text) {
        List<Integer> refs = new ArrayList<>();
        Matcher m = MARKER.matcher(text);
        while (m.find()) {
            for (String n : m.group(1).split("\\s*,\\s*")) {
                refs.add(Integer.parseInt(n));
            }
        }
        return refs;
    }

    /** Blank-line separated blocks; every list item starts its own paragraph. */
    private static List<String> paragraphs(String answer) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : answer.split("\\R", -1)) {
            if (line.isBlank()) {
                flush(out, current);
            } else if (LIST_ITEM.matcher(line).matches()) {
                flush(out, current);
                current.append(line);
            } else {
                current.append(current.isEmpty() ? "" : " ").append(line);
            }
        }
        flush(out, current);
        return out;
    }

    private static void flush(List<String> out, StringBuilder current) {
        if (!current.isEmpty()) {
            out.add(current.toString());
            current.setLength(0);
        }
    }
}
