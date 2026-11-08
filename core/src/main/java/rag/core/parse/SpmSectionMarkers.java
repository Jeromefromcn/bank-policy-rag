package rag.core.parse;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import rag.core.model.SectionMarker;
import rag.core.model.SectionPath;

/**
 * Finds numbered-paragraph markers in SPM module text (spec 01 §3.3).
 *
 * <ul>
 *   <li>A marker line starts with {@code N.} or {@code N.M[.K…]} followed by text. Top-level numbers
 *       need the trailing dot, which excludes footnote lines and page numbers.
 *   <li>A marker is accepted only if it is greater than the previous accepted one, which rejects
 *       numbers that happen to start a wrapped line.
 *   <li>Every module lists its contents under a {@code Structure} line before the body. That list
 *       restarts at {@code 1.}; the body starts at the second {@code 1.} line after {@code Structure}.
 * </ul>
 */
public final class SpmSectionMarkers {

    private static final Pattern STRUCTURE = Pattern.compile("(?m)^\\s*Structure\\s*$");
    private static final Pattern TOP_ONE = Pattern.compile("(?m)^\\s*1\\.\\s");
    private static final Pattern MARKER = Pattern.compile("(?m)^[ \\t]*(\\d+\\.|\\d+(?:\\.\\d+)+)[ \\t]+\\S");

    private SpmSectionMarkers() {}

    public static List<SectionMarker> find(String text) {
        int bodyStart = bodyStart(text);
        List<SectionMarker> markers = new ArrayList<>();
        SectionPath last = SectionPath.empty();
        Matcher m = MARKER.matcher(text);
        m.region(bodyStart, text.length());
        while (m.find()) {
            String number = m.group(1);
            if (number.endsWith(".")) {
                number = number.substring(0, number.length() - 1);
            }
            SectionPath path = SectionPath.parse(number);
            if (path.compareTo(last) > 0) {
                markers.add(new SectionMarker(m.start(1), path));
                last = path;
            }
        }
        return markers;
    }

    private static int bodyStart(String text) {
        Matcher structure = STRUCTURE.matcher(text);
        if (!structure.find()) {
            throw new IllegalStateException("body start not found: no 'Structure' line");
        }
        Matcher one = TOP_ONE.matcher(text);
        one.region(structure.end(), text.length());
        if (!one.find() || !one.find()) {
            throw new IllegalStateException("body start not found: no second '1.' line after 'Structure'");
        }
        return one.start();
    }
}
