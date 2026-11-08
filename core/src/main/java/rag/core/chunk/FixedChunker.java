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
 * Fixed-length chunking over whitespace-delimited words (spec 01 §3.4). A window grows until the
 * next word would exceed {@code maxTokens}; the next window starts at the latest word that keeps at
 * least {@code overlapTokens} of overlap. A single word longer than the limit becomes its own chunk.
 */
public final class FixedChunker implements Chunker {

    private static final Pattern WORD = Pattern.compile("\\S+");

    private final TokenCounter tokens;
    private final int maxTokens;
    private final int overlapTokens;

    public FixedChunker(TokenCounter tokens, int maxTokens, int overlapTokens) {
        if (overlapTokens >= maxTokens) {
            throw new IllegalArgumentException("overlap must be smaller than max tokens");
        }
        this.tokens = tokens;
        this.maxTokens = maxTokens;
        this.overlapTokens = overlapTokens;
    }

    @Override
    public String version() {
        return "fixed-" + maxTokens + "-" + overlapTokens + "-cl100k";
    }

    @Override
    public List<Chunk> chunk(ParsedDocument doc) {
        String text = doc.text();
        List<int[]> words = new ArrayList<>();
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            words.add(new int[] {m.start(), m.end()});
        }

        List<Chunk> chunks = new ArrayList<>();
        int i = 0;
        while (i < words.size()) {
            int j = lastFitting(text, words, i);
            chunks.add(build(doc, chunks.size(), words.get(i)[0], words.get(j)[1]));
            if (j == words.size() - 1) {
                break;
            }
            int next = overlapStart(text, words, i, j);
            i = next > i ? next : j + 1;
        }
        return chunks;
    }

    /** Largest j ≥ i with tokens(i..j) ≤ max; i itself if even one word exceeds the limit. */
    private int lastFitting(String text, List<int[]> words, int i) {
        int lo = i;
        int hi = Math.min(words.size() - 1, i + maxTokens);
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (count(text, words, i, mid) <= maxTokens) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    /** Largest k in (i, j] with tokens(k..j) ≥ overlap, or -1 if none. */
    private int overlapStart(String text, List<int[]> words, int i, int j) {
        int lo = i + 1;
        int hi = j;
        int found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (count(text, words, mid, j) >= overlapTokens) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }

    private int count(String text, List<int[]> words, int from, int to) {
        return tokens.count(text.substring(words.get(from)[0], words.get(to)[1]));
    }

    private Chunk build(ParsedDocument doc, int ordinal, int start, int end) {
        String content = doc.text().substring(start, end);

        SectionPath atStart = SectionPath.empty();
        Set<String> covered = new LinkedHashSet<>();
        for (SectionMarker marker : doc.markers()) {
            if (marker.offset() <= start) {
                atStart = marker.path();
            } else if (marker.offset() < end) {
                if (covered.isEmpty() && !atStart.isEmpty()) {
                    covered.add(atStart.leaf());
                }
                covered.add(marker.path().leaf());
            }
        }
        if (covered.isEmpty() && !atStart.isEmpty()) {
            covered.add(atStart.leaf());
        }

        List<Integer> pages = new ArrayList<>();
        for (PageSpan span : doc.pages()) {
            if (start < span.end() && end > span.start()) {
                pages.add(span.page());
            }
        }

        String sectionId = Hashes.sectionId(doc.docId(), atStart);
        return new Chunk(Hashes.chunkId(sectionId, ordinal, version()), doc.docId(), sectionId, ordinal, content,
                Hashes.sha256Hex(content), tokens.count(content), start, end, pages, atStart, List.copyOf(covered));
    }
}
