package rag.core.chunk;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import rag.core.ids.Hashes;
import rag.core.model.PageSpan;
import rag.core.model.ParsedDocument;
import rag.core.model.SectionMarker;
import rag.core.model.SectionPath;

/**
 * Structure-aware chunking (spec 02 §3). A unit is the text between two section markers. Units
 * below {@code minTokens} merge into the next sibling (or the previous one when last) within the
 * same depth-1 parent; units above {@code maxTokens} split on sentence boundaries. Text before the
 * first marker is not indexed; a document with no markers is chunked whole under an empty path.
 * Chunk ordinals count within the parent, and {@code sectionId} is the parent's id.
 */
public final class StructuralChunker implements Chunker {

    public static final int DEFAULT_MIN_TOKENS = 60;

    /** Ends of sentences: after terminal punctuation, or at a line break. */
    private static final Pattern BREAK = Pattern.compile("(?<=[.!?])\\s+|\\n+");

    /** A trimmed slice of the document text with the sections it covers. */
    private record Piece(int start, int end, SectionPath path, List<String> covered) {}

    private final TokenCounter tokens;
    private final int maxTokens;
    private final int minTokens;

    public StructuralChunker(TokenCounter tokens, int maxTokens, int minTokens) {
        if (minTokens >= maxTokens) {
            throw new IllegalArgumentException("min tokens must be smaller than max tokens");
        }
        this.tokens = tokens;
        this.maxTokens = maxTokens;
        this.minTokens = minTokens;
    }

    /** The minimum is part of the version only when it is not the v1 default, so default ids stay stable. */
    @Override
    public String version() {
        String v = "structural-v1-" + maxTokens + "-cl100k";
        return minTokens == DEFAULT_MIN_TOKENS ? v : v + "-min" + minTokens;
    }

    @Override
    public List<Chunk> chunk(ParsedDocument doc) {
        List<Chunk> chunks = new ArrayList<>();
        List<Piece> group = new ArrayList<>();
        SectionPath groupPath = null;
        for (Piece unit : units(doc)) {
            SectionPath parent = SectionParents.parentPath(unit.path());
            if (groupPath != null && !parent.equals(groupPath)) {
                emit(doc, groupPath, group, chunks);
                group.clear();
            }
            groupPath = parent;
            group.add(unit);
        }
        if (groupPath != null) {
            emit(doc, groupPath, group, chunks);
        }
        return chunks;
    }

    private List<Piece> units(ParsedDocument doc) {
        String text = doc.text();
        List<Piece> units = new ArrayList<>();
        if (doc.markers().isEmpty()) {
            addTrimmed(units, text, 0, text.length(), SectionPath.empty());
            return units;
        }
        List<SectionMarker> markers = doc.markers();
        for (int i = 0; i < markers.size(); i++) {
            int end = i + 1 < markers.size() ? markers.get(i + 1).offset() : text.length();
            addTrimmed(units, text, markers.get(i).offset(), end, markers.get(i).path());
        }
        return units;
    }

    private static void addTrimmed(List<Piece> out, String text, int start, int end, SectionPath path) {
        while (start < end && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        if (start < end) {
            out.add(new Piece(start, end, path, path.isEmpty() ? List.of() : List.of(path.leaf())));
        }
    }

    private void emit(ParsedDocument doc, SectionPath parent, List<Piece> units, List<Chunk> chunks) {
        String text = doc.text();
        List<Piece> pieces = new ArrayList<>();
        for (Piece unit : units) {
            if (count(text, unit) > maxTokens) {
                pieces.addAll(split(text, unit));
            } else {
                pieces.add(unit);
            }
        }

        List<Piece> packed = new ArrayList<>();
        int i = 0;
        while (i < pieces.size()) {
            Piece cur = pieces.get(i++);
            while (count(text, cur) < minTokens && i < pieces.size()) {
                Piece merged = merge(cur, pieces.get(i));
                if (count(text, merged) > maxTokens) {
                    break;
                }
                cur = merged;
                i++;
            }
            if (count(text, cur) < minTokens && !packed.isEmpty()) {
                Piece merged = merge(packed.getLast(), cur);
                if (count(text, merged) <= maxTokens) {
                    packed.set(packed.size() - 1, merged);
                    continue;
                }
            }
            packed.add(cur);
        }

        String sectionId = Hashes.sectionId(doc.docId(), parent);
        for (int ordinal = 0; ordinal < packed.size(); ordinal++) {
            chunks.add(build(doc, sectionId, ordinal, packed.get(ordinal)));
        }
    }

    /** Splits an oversize unit into sentence-aligned windows of at most {@code maxTokens}. */
    private List<Piece> split(String text, Piece unit) {
        List<int[]> sentences = new ArrayList<>();
        Matcher m = BREAK.matcher(text).region(unit.start(), unit.end());
        int from = unit.start();
        while (m.find()) {
            addSentence(sentences, text, from, m.start());
            from = m.end();
        }
        addSentence(sentences, text, from, unit.end());

        List<Piece> out = new ArrayList<>();
        int winStart = sentences.getFirst()[0];
        int winEnd = sentences.getFirst()[1];
        for (int k = 1; k < sentences.size(); k++) {
            int[] s = sentences.get(k);
            if (tokens.count(text.substring(winStart, s[1])) > maxTokens) {
                out.add(new Piece(winStart, winEnd, unit.path(), unit.covered()));
                winStart = s[0];
            }
            winEnd = s[1];
        }
        out.add(new Piece(winStart, winEnd, unit.path(), unit.covered()));
        return out;
    }

    private static void addSentence(List<int[]> out, String text, int start, int end) {
        while (start < end && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        if (start < end) {
            out.add(new int[] {start, end});
        }
    }

    private static Piece merge(Piece a, Piece b) {
        Set<String> covered = new LinkedHashSet<>(a.covered());
        covered.addAll(b.covered());
        return new Piece(a.start(), b.end(), a.path(), List.copyOf(covered));
    }

    private int count(String text, Piece p) {
        return tokens.count(text.substring(p.start(), p.end()));
    }

    private Chunk build(ParsedDocument doc, String sectionId, int ordinal, Piece p) {
        String content = doc.text().substring(p.start(), p.end());
        List<Integer> pages = new ArrayList<>();
        for (PageSpan span : doc.pages()) {
            if (p.start() < span.end() && p.end() > span.start()) {
                pages.add(span.page());
            }
        }
        return new Chunk(Hashes.chunkId(sectionId, ordinal, version()), doc.docId(), sectionId, ordinal, content,
                Hashes.sha256Hex(content), tokens.count(content), p.start(), p.end(), pages, p.path(), p.covered());
    }
}
