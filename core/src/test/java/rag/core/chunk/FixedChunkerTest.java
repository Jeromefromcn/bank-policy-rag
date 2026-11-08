package rag.core.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import rag.core.model.Clearance;
import rag.core.model.PageSpan;
import rag.core.model.ParsedDocument;
import rag.core.model.SectionMarker;
import rag.core.model.SectionPath;
import rag.core.model.SourceType;

class FixedChunkerTest {

    private final TokenCounter tc = TokenCounter.cl100k();
    private final FixedChunker chunker = new FixedChunker(tc, 50, 10);

    private static String words(int n) {
        return IntStream.range(0, n).mapToObj(i -> "w" + i).collect(Collectors.joining(" "));
    }

    private static ParsedDocument doc(String text, List<SectionMarker> markers, List<PageSpan> pages) {
        return new ParsedDocument("d-1", "Doc", "1", SourceType.INTERNAL, Clearance.INTERNAL, text, pages, markers);
    }

    private static SectionMarker marker(int offset, String number) {
        return new SectionMarker(offset, SectionPath.parse(number));
    }

    @Test
    void windowsRespectMaxAndOverlap() {
        var d = doc(words(2000), List.of(), List.of());
        var chunks = chunker.chunk(d);
        assertThat(chunks).hasSizeGreaterThan(10);
        assertThat(chunks).allSatisfy(c -> assertThat(c.tokenCount()).isLessThanOrEqualTo(50));
        for (int i = 1; i < chunks.size(); i++) {
            var prev = chunks.get(i - 1);
            var cur = chunks.get(i);
            assertThat(cur.start()).isGreaterThan(prev.start()).isLessThan(prev.end());
            assertThat(tc.count(d.text().substring(cur.start(), prev.end()))).isGreaterThanOrEqualTo(10);
        }
        assertThat(chunks.getFirst().start()).isZero();
        assertThat(chunks.getLast().end()).isEqualTo(d.text().stripTrailing().length());
    }

    @Test
    void contentIsExactSourceSlice() {
        var d = doc(words(500), List.of(), List.of());
        assertThat(chunker.chunk(d)).allSatisfy(c -> {
            assertThat(c.content()).isEqualTo(d.text().substring(c.start(), c.end()));
            assertThat(c.tokenCount()).isEqualTo(tc.count(c.content()));
        });
    }

    @Test
    void ordinalsAndIdsAreSequentialAndUnique() {
        var chunks = chunker.chunk(doc(words(500), List.of(), List.of()));
        assertThat(chunks).extracting(Chunk::ordinal).containsExactlyElementsOf(IntStream.range(0, chunks.size()).boxed().toList());
        assertThat(chunks).extracting(Chunk::chunkId).doesNotHaveDuplicates();
        assertThat(chunks).allSatisfy(c -> assertThat(c.docId()).isEqualTo("d-1"));
    }

    @Test
    void sectionAttribution() {
        String text = words(300);
        var plain = chunker.chunk(doc(text, List.of(), List.of()));
        var c0 = plain.get(0);
        var c1 = plain.get(1);
        int inC0 = text.indexOf(" w10 ") + 1;                       // inside chunk 0, before chunk 1 starts
        assertThat(inC0).isLessThan(c1.start());
        int inC1Only = text.indexOf(' ', c0.end() + 1) + 1;        // a word start inside chunk 1, after chunk 0 ends
        assertThat(inC1Only).isLessThan(c1.end());

        var chunks = chunker.chunk(doc(text, List.of(marker(0, "1"), marker(inC0, "1.1"), marker(inC1Only, "2")), List.of()));

        assertThat(chunks.get(0).sectionPath().leaf()).isEqualTo("1");
        assertThat(chunks.get(0).coveredSections()).containsExactly("1", "1.1");
        assertThat(chunks.get(1).sectionPath().leaf()).isEqualTo("1.1");
        assertThat(chunks.get(1).coveredSections()).containsExactly("1.1", "2");
        assertThat(chunks.get(1).sectionId()).isEqualTo(rag.core.ids.Hashes.sectionId("d-1", SectionPath.parse("1.1")));
    }

    @Test
    void textBeforeFirstMarkerHasEmptySection() {
        String text = words(30);
        int mid = text.indexOf(" w20 ") + 1;
        var chunks = chunker.chunk(doc(text, List.of(marker(mid, "3")), List.of()));
        assertThat(chunks.getFirst().sectionPath().isEmpty()).isTrue();
        assertThat(chunks.getFirst().coveredSections()).containsExactly("3");
    }

    @Test
    void pagesAreOverlappingSpans() {
        String p1 = words(30);
        String p2 = words(30).replace('w', 'v');
        String text = p1 + "\n" + p2;
        var pages = List.of(new PageSpan(1, 0, p1.length()), new PageSpan(2, p1.length() + 1, text.length()));
        var chunks = new FixedChunker(tc, 500, 10).chunk(doc(text, List.of(), pages));
        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().pages()).containsExactly(1, 2);
        assertThat(chunker.chunk(doc(words(200), List.of(), List.of()))).allSatisfy(c -> assertThat(c.pages()).isEmpty());
    }

    @Test
    void oversizeWordBecomesItsOwnChunk() {
        String huge = "1234567890".repeat(300);
        assertThat(tc.count(huge)).isGreaterThan(50);
        String text = words(40) + " " + huge + " " + words(40);
        var chunks = chunker.chunk(doc(text, List.of(), List.of()));
        assertThat(chunks).anySatisfy(c -> assertThat(c.content()).isEqualTo(huge));
        assertThat(chunks.getLast().end()).isEqualTo(text.length());
        List<int[]> ranges = new ArrayList<>();
        chunks.forEach(c -> ranges.add(new int[] {c.start(), c.end()}));
        for (int i = 1; i < ranges.size(); i++) {
            assertThat(ranges.get(i)[0]).isLessThanOrEqualTo(ranges.get(i - 1)[1] + 1);  // no gap beyond one space
        }
    }

    @Test
    void emptyTextHasNoChunks() {
        assertThat(chunker.chunk(doc("   \n ", List.of(), List.of()))).isEmpty();
    }

    @Test
    void deterministic() {
        var d = doc(words(800), List.of(marker(0, "1")), List.of());
        assertThat(chunker.chunk(d)).isEqualTo(chunker.chunk(d));
    }

    @Test
    void versionString() {
        assertThat(new FixedChunker(tc, 512, 64).version()).isEqualTo("fixed-512-64-cl100k");
    }
}
