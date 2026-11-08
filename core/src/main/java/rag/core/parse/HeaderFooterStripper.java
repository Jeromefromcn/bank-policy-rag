package rag.core.parse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Removes running headers, footers and page numbers from per-page text lines (spec 01 §3.1).
 *
 * <p>Zones are the first and last {@value #ZONE} non-blank lines of a page. A zone line is removed
 * when its digit-normalised form occurs in the same zone on at least {@value #THRESHOLD} of the
 * pages. A digits-only line in the bottom zone is a page number and is always removed.
 */
public final class HeaderFooterStripper {

    static final int ZONE = 3;
    static final double THRESHOLD = 0.6;

    private HeaderFooterStripper() {}

    public static List<List<String>> strip(List<List<String>> pages) {
        Map<String, Integer> top = new HashMap<>();
        Map<String, Integer> bottom = new HashMap<>();
        for (List<String> page : pages) {
            countDistinct(top, zone(page, true), page);
            countDistinct(bottom, zone(page, false), page);
        }
        int minPages = (int) Math.ceil(THRESHOLD * pages.size());

        List<List<String>> result = new ArrayList<>(pages.size());
        for (List<String> page : pages) {
            Set<Integer> remove = new HashSet<>();
            for (int i : zone(page, true)) {
                if (top.get(normalise(page.get(i))) >= minPages) {
                    remove.add(i);
                }
            }
            for (int i : zone(page, false)) {
                String line = page.get(i);
                if (line.strip().matches("\\d+") || bottom.get(normalise(line)) >= minPages) {
                    remove.add(i);
                }
            }
            List<String> kept = new ArrayList<>();
            for (int i = 0; i < page.size(); i++) {
                if (!remove.contains(i)) {
                    kept.add(page.get(i));
                }
            }
            result.add(trimBlankEdges(kept));
        }
        return result;
    }

    /** Indexes of the zone's non-blank lines. */
    private static List<Integer> zone(List<String> page, boolean fromTop) {
        List<Integer> idx = new ArrayList<>(ZONE);
        for (int k = 0; k < page.size() && idx.size() < ZONE; k++) {
            int i = fromTop ? k : page.size() - 1 - k;
            if (!page.get(i).isBlank()) {
                idx.add(i);
            }
        }
        return idx;
    }

    private static void countDistinct(Map<String, Integer> counts, List<Integer> zone, List<String> page) {
        Set<String> seen = new HashSet<>();
        for (int i : zone) {
            seen.add(normalise(page.get(i)));
        }
        seen.forEach(s -> counts.merge(s, 1, Integer::sum));
    }

    private static String normalise(String line) {
        return line.strip().replaceAll("\\s+", " ").replaceAll("\\d+", "#");
    }

    private static List<String> trimBlankEdges(List<String> lines) {
        int from = 0;
        int to = lines.size();
        while (from < to && lines.get(from).isBlank()) {
            from++;
        }
        while (to > from && lines.get(to - 1).isBlank()) {
            to--;
        }
        return List.copyOf(lines.subList(from, to));
    }
}
