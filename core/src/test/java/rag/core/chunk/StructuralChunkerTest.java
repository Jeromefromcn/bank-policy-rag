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

class StructuralChunkerTest {

    private static final int MAX = 100;
    private static final int MIN = 30;

    private final TokenCounter tc = TokenCounter.cl100k();
    private final StructuralChunker chunker = new StructuralChunker(tc, MAX, MIN);

    /** Builds text and markers together so offsets are always right. */
    private static final class Doc {
        final StringBuilder text = new StringBuilder();
        final List<SectionMarker> markers = new ArrayList<>();

        Doc preface(String s) {
            text.append(s).append('\n');
            return this;
        }

        Doc section(String number, String body) {
            markers.add(new SectionMarker(text.length(), SectionPath.parse(number)));
            text.append(number).append(". ").append(body).append('\n');
            return this;
        }

        ParsedDocument build() {
            return new ParsedDocument("d-1", "Doc", "1", SourceType.INTERNAL, Clearance.INTERNAL, text.toString(), List.of(), markers);
        }
    }

    /** n sentences of about ten tokens each. */
    private static String sentences(String tag, int n) {
        return IntStream.range(0, n).mapToObj(i -> "Sentence " + tag + i + " covers a plain topic here.")
                .collect(Collectors.joining(" "));
    }

    @Test
    void oneChunkPerUnitWhenEachFitsTheLimits() {
        var d = new Doc().section("1.1", sentences("a", 6)).section("1.2", sentences("b", 6)).section("2.1", sentences("c", 6)).build();

        var chunks = chunker.chunk(d);

        assertThat(chunks).hasSize(3);
        assertThat(chunks).extracting(c -> c.sectionPath().components())
                .containsExactly(List.of("1", "1.1"), List.of("1", "1.2"), List.of("2", "2.1"));
        assertThat(chunks.get(0).sectionId()).isEqualTo(chunks.get(1).sectionId()).isNotEqualTo(chunks.get(2).sectionId());
        assertThat(chunks).allSatisfy(c -> assertThat(c.content()).isEqualTo(d.text().substring(c.start(), c.end())));
    }

    @Test
    void textBeforeTheFirstMarkerIsNotIndexed() {
        var d = new Doc().preface("Preface words that must not be indexed anywhere.").section("1.1", sentences("a", 6)).build();

        var chunks = chunker.chunk(d);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().content()).doesNotContain("Preface");
        assertThat(chunks.getFirst().start()).isEqualTo(d.markers().getFirst().offset());
    }

    @Test
    void shortUnitMergesIntoTheNextSiblingKeepingTheFirstPath() {
        var d = new Doc().section("1.1", "Tiny.").section("1.2", sentences("b", 6)).build();

        var chunks = chunker.chunk(d);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().sectionPath().leaf()).isEqualTo("1.1");
        assertThat(chunks.getFirst().coveredSections()).containsExactly("1.1", "1.2");
    }

    @Test
    void mergesLastShortUnitBackwardWithinParent() {
        var d = new Doc().section("1.1", sentences("a", 6)).section("1.2", "Tiny.").build();

        var chunks = chunker.chunk(d);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().coveredSections()).containsExactly("1.1", "1.2");
    }

    @Test
    void shortParentWithASingleUnitStaysAsItIs() {
        var d = new Doc().section("1.1", sentences("a", 6)).section("2.1", "Tiny.").build();

        var chunks = chunker.chunk(d);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(1).content()).contains("Tiny.");
    }

    @Test
    void noChunkCrossesParents() {
        var d = new Doc().section("1.1", "Tiny.").section("2.1", "Tiny.").section("2.2", sentences("b", 6)).build();

        var chunks = chunker.chunk(d);

        assertThat(chunks).hasSize(2);
        assertThat(chunks).allSatisfy(c -> assertThat(c.coveredSections().stream().map(s -> s.split("\\.")[0]).distinct()).hasSize(1));
        assertThat(chunks.get(0).sectionId()).isNotEqualTo(chunks.get(1).sectionId());
    }

    @Test
    void mergeNeverExceedsTheMaximum() {
        // two near-full units (above MIN, so nothing merges forward); the short tail does not fit into either
        var d = new Doc().section("1.1", sentences("a", 10)).section("1.2", sentences("b", 10)).section("1.3", sentences("t", 2)).build();

        var chunks = chunker.chunk(d);

        assertThat(chunks).allSatisfy(c -> assertThat(c.tokenCount()).isLessThanOrEqualTo(MAX));
        assertThat(chunks).hasSize(3);
    }

    @Test
    void coversEveryCharacterAfterTheFirstMarker() {
        var d = new Doc().preface("Preface.").section("1", "Heading").section("1.1", sentences("a", 25))
                .section("1.2", "Tiny.").section("2.1", sentences("b", 4)).build();

        var chunks = chunker.chunk(d);

        int from = d.markers().getFirst().offset();
        for (int i = from; i < d.text().length(); i++) {
            if (Character.isWhitespace(d.text().charAt(i))) {
                continue;
            }
            int pos = i;
            assertThat(chunks).anySatisfy(c -> assertThat(pos).isBetween(c.start(), c.end() - 1));
        }
    }

    @Test
    void oversizeUnitSplitsOnSentenceBoundaries() {
        var d = new Doc().section("1.1", sentences("a", 60)).build();

        var chunks = chunker.chunk(d);

        assertThat(chunks).hasSizeGreaterThan(3);
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.tokenCount()).isLessThanOrEqualTo(MAX);
            assertThat(c.content()).endsWith(".");
            assertThat(c.sectionPath().leaf()).isEqualTo("1.1");
            assertThat(c.coveredSections()).containsExactly("1.1");
        });
        assertThat(chunks).extracting(Chunk::ordinal).containsExactlyElementsOf(IntStream.range(0, chunks.size()).boxed().toList());
        assertThat(chunks).extracting(Chunk::chunkId).doesNotHaveDuplicates();
    }

    @Test
    void sentenceLongerThanTheLimitBecomesItsOwnChunk() {
        String monster = IntStream.range(0, 300).mapToObj(i -> "word" + i).collect(Collectors.joining(" "));
        var d = new Doc().section("1.1", "Short lead sentence here. " + monster + ". Short tail sentence here.").build();

        var chunks = chunker.chunk(d);

        assertThat(chunks).anySatisfy(c -> assertThat(c.tokenCount()).isGreaterThan(MAX));
        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void ordinalsAreUniquePerParent() {
        var d = new Doc().section("1.1", sentences("a", 6)).section("1.2", sentences("b", 6)).section("2.1", sentences("c", 6)).build();

        var chunks = chunker.chunk(d);

        assertThat(chunks).extracting(Chunk::chunkId).doesNotHaveDuplicates();
        assertThat(chunks.get(0).ordinal()).isZero();
        assertThat(chunks.get(1).ordinal()).isEqualTo(1);
        assertThat(chunks.get(2).ordinal()).isZero();
    }

    @Test
    void headingOnlyMarkdownDocumentWorks() {
        var d = new Doc().preface("# Title").section("1", sentences("a", 6)).section("2", sentences("b", 6)).build();

        var chunks = chunker.chunk(d);

        assertThat(chunks).extracting(c -> c.sectionPath().components()).containsExactly(List.of("1"), List.of("2"));
        assertThat(chunks).extracting(Chunk::coveredSections).containsExactly(List.of("1"), List.of("2"));
    }

    @Test
    void documentWithoutMarkersIsChunkedWhole() {
        var d = new Doc().preface(sentences("a", 25)).build();

        var chunks = chunker.chunk(d);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.sectionPath().isEmpty()).isTrue();
            assertThat(c.coveredSections()).isEmpty();
        });
    }

    @Test
    void pagesComeFromOverlappingSpans() {
        var b = new Doc().section("1.1", sentences("a", 6)).section("1.2", sentences("b", 6));
        int split = b.markers.get(1).offset();
        var d = new ParsedDocument("d-1", "Doc", "1", SourceType.REGULATORY, Clearance.PUBLIC, b.text.toString(),
                List.of(new PageSpan(4, 0, split), new PageSpan(5, split, b.text.length())), b.markers);

        var chunks = chunker.chunk(d);

        assertThat(chunks.get(0).pages()).containsExactly(4);
        assertThat(chunks.get(1).pages()).containsExactly(5);
    }

    @Test
    void isDeterministic() {
        var d = new Doc().section("1.1", sentences("a", 60)).section("1.2", "Tiny.").section("2.1", sentences("c", 6)).build();

        var first = chunker.chunk(d);
        var second = new StructuralChunker(TokenCounter.cl100k(), MAX, MIN).chunk(d);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void versionNamesTheLimitsAndStaysStableForTheDefaultMinimum() {
        assertThat(new StructuralChunker(tc, 400, 60).version()).isEqualTo("structural-v1-400-cl100k");
        assertThat(new StructuralChunker(tc, 400, 40).version()).isNotEqualTo("structural-v1-400-cl100k");
    }

    @Test
    void chunkSectionIdEqualsTheParentsSectionId() {
        var d = new Doc().section("1.1", sentences("a", 6)).section("2.1", sentences("c", 6)).build();

        var chunks = chunker.chunk(d);
        var parents = SectionParents.of(d);

        assertThat(parents).extracting(SectionParent::sectionId).containsExactlyElementsOf(chunks.stream().map(Chunk::sectionId).toList());
    }
}
