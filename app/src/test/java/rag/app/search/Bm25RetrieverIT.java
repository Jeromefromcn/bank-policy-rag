package rag.app.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import rag.app.es.ChunkDocument;
import rag.app.es.ChunkIndexAdmin;
import rag.app.es.ChunkWriter;
import rag.app.security.Principal;
import rag.app.support.FakeEmbedder;
import rag.app.support.IntegrationTest;
import rag.app.support.TestBeans;
import rag.app.support.TestIndex;
import rag.core.model.Clearance;

@TestPropertySource(properties = "rag.env-prefix=t_bm25")
class Bm25RetrieverIT extends IntegrationTest {

    static final Principal PUBLIC = new Principal("bob", Clearance.PUBLIC, Set.of());
    static final Principal RESTRICTED = new Principal("alice", Clearance.RESTRICTED, Set.of());

    @Autowired Bm25Retriever retriever;
    @Autowired ChunkIndexAdmin admin;
    @Autowired ChunkWriter writer;

    static ChunkDocument titled(String chunkId, String docTitle, String classification, String content) {
        return new ChunkDocument(chunkId, "s-" + chunkId, "doc-" + chunkId, docTitle, "1", "regulatory", List.of("1"), List.of("1"),
                List.of(), classification, "abcdef123456", 0, content, "h" + chunkId, 5,
                new FakeEmbedder(TestBeans.DIMS).vector(content), "fake/hash", "fixed-512-64-cl100k", "2026-11-08T00:00:00Z");
    }

    @BeforeEach
    void index() {
        List<ChunkDocument> docs = new ArrayList<>(TestIndex.mixed("tolerance for disruption"));
        docs.add(titled("c-secret", "Internal Note", "internal", "quarterly xylophone reconciliation procedure"));
        docs.add(titled("c-title", "ZX-9 Sample Module", "public", "unrelated body words only"));
        TestIndex.rebuild(admin, writer, "t_bm25", docs);
    }

    @Test
    void publicCallerNeverSeesInternalOnlyMatch() {
        assertThat(retriever.search("xylophone reconciliation", 10, PUBLIC)).isEmpty();
    }

    @Test
    void restrictedCallerSeesTheSameMatch() {
        var hits = retriever.search("xylophone reconciliation", 10, RESTRICTED);
        assertThat(hits).extracting(SearchHit::chunkId).containsExactly("c-secret");
    }

    @Test
    void aModuleCodeThatOnlyAppearsInTheTitleIsFound() {
        var hits = retriever.search("ZX-9", 10, PUBLIC);
        assertThat(hits).isNotEmpty();
        assertThat(hits.getFirst().chunkId()).isEqualTo("c-title");
    }

    @Test
    void ranksAndScoresArePopulatedBestFirst() {
        var hits = retriever.search("tolerance disruption", 10, RESTRICTED);
        assertThat(hits).hasSizeGreaterThan(1);
        for (int i = 0; i < hits.size(); i++) {
            assertThat(hits.get(i).scores().get("bm25Rank")).isEqualTo(i + 1);
            assertThat((double) hits.get(i).scores().get("bm25Score")).isPositive();
            assertThat(hits.get(i).scores()).doesNotContainKey("vectorRank");
            if (i > 0) {
                assertThat(hits.get(i).score()).isLessThanOrEqualTo(hits.get(i - 1).score());
            }
        }
        assertThat(hits.getFirst().chunkId()).isEqualTo("c-int");
    }

    @Test
    void sizeLimitsTheNumberOfHits() {
        assertThat(retriever.search("tolerance disruption", 2, RESTRICTED)).hasSize(2);
    }

    @Test
    void aQueryOfOnlyStopWordsReturnsNothing() {
        assertThat(retriever.search("the of and", 10, RESTRICTED)).isEmpty();
    }

    @Test
    void missingAliasReturnsNoHits() {
        admin.delete(admin.aliasInfo("t_bm25-chunks").orElseThrow().index());
        assertThat(retriever.search("tolerance", 10, RESTRICTED)).isEmpty();
    }
}
