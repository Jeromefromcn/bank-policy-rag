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

    /** {@code [1]}, {@code [ 1 ]}, {@code [1, 3]}, {@code [1-3]}: one to three digits, so {@code [2024]} is not a citation. */
    private static final String MARKER_BODY = "\\[\\s*(\\d{1,3}(?:\\s*[,\\-\u2013]\\s*\\d{1,3})*)\\s*]";
    private static final Pattern MARKER = Pattern.compile(MARKER_BODY);
    /** A terminator plus any citation markers glued to it ({@code "limit. [1]"}, {@code "applies.[1]"}). */
    private static final Pattern TERMINATOR = Pattern.compile("[.!?](?:\\s*" + MARKER_BODY.replace("(\\d", "(?:\\d") + ")*");
    private static final Pattern NEXT_SENTENCE = Pattern.compile("\\s+[A-Z]");
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:[-*•]|\\d+[.)])\\s+.*");

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
        for (String raw : sentences(answer)) {
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

    /** Splits on line breaks and on a terminator (with its glued markers) followed by an upper-case word. */
    static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\\R")) {
            int start = 0;
            Matcher m = TERMINATOR.matcher(line);
            while (m.find()) {
                int end = m.end();
                if (end == line.length() || NEXT_SENTENCE.matcher(line).region(end, line.length()).lookingAt()) {
                    out.add(line.substring(start, end));
                    start = end;
                }
            }
            if (start < line.length() && !line.substring(start).isBlank()) {
                out.add(line.substring(start));
            }
        }
        return out;
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
            for (String part : m.group(1).split("\\s*,\\s*")) {
                String[] ends = part.split("\\s*[\\-\u2013]\\s*");
                int from = Integer.parseInt(ends[0]);
                int to = ends.length > 1 ? Integer.parseInt(ends[1]) : from;
                if (to < from || to - from > 50) {
                    refs.add(from);
                    refs.add(to);
                } else {
                    for (int n = from; n <= to; n++) {
                        refs.add(n);
                    }
                }
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
                // A lead-in such as "The policy requires:" belongs to the first item that follows it.
                if (current.isEmpty() || !current.toString().stripTrailing().endsWith(":")) {
                    flush(out, current);
                }
                current.append(current.isEmpty() ? "" : " ").append(line);
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
