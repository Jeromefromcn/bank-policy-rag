package rag.core.cite;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Content-word tokens for the lexical overlap check: lower-cased, stop words removed, light suffix stripping. */
final class Tokens {

    private static final Set<String> STOP = Set.of("a", "an", "the", "and", "or", "of", "to", "in", "on", "for", "by", "with",
            "is", "are", "was", "were", "be", "been", "it", "its", "this", "that", "these", "those", "as", "at", "from",
            "must", "should", "shall", "may", "can", "will", "not", "no", "any", "all", "also", "which", "who", "than", "then");

    private Tokens() {}

    static List<String> of(String text) {
        List<String> out = new ArrayList<>();
        for (String raw : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (raw.isEmpty() || STOP.contains(raw)) {
                continue;
            }
            out.add(stem(raw));
        }
        return out;
    }

    private static String stem(String t) {
        for (String suffix : new String[] {"ing", "ed", "es", "s"}) {
            if (t.endsWith(suffix) && t.length() - suffix.length() >= 3) {
                return t.substring(0, t.length() - suffix.length());
            }
        }
        return t;
    }
}
