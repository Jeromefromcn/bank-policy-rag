package rag.app.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import rag.app.es.ChunkIndexAdmin;
import rag.app.es.ChunkWriter;
import rag.app.security.Principal;
import rag.app.support.IntegrationTest;
import rag.app.support.TestIndex;
import rag.core.model.Clearance;

@TestPropertySource(properties = "rag.env-prefix=t_vec")
class VectorRetrieverIT extends IntegrationTest {

    static final String QUERY = "tolerance for disruption";
    static final Principal PUBLIC = new Principal("bob", Clearance.PUBLIC, Set.of());
    static final Principal RESTRICTED = new Principal("alice", Clearance.RESTRICTED, Set.of());

    @Autowired VectorRetriever retriever;
    @Autowired ChunkIndexAdmin admin;
    @Autowired ChunkWriter writer;

    @BeforeEach
    void index() {
        TestIndex.rebuild(admin, writer, "t_vec", TestIndex.mixed(QUERY));
    }

    @Test
    void preFilterExcludesHigherClassificationAndStillFillsTopK() {
        var hits = retriever.search(QUERY, 5, PUBLIC);
        assertThat(hits).hasSize(5).allSatisfy(h -> assertThat(h.classification()).isEqualTo("public"));
    }

    @Test
    void restrictedSeesInternalFirst() {
        var first = retriever.search(QUERY, 5, RESTRICTED).getFirst();
        assertThat(first.classification()).isEqualTo("internal");
        assertThat(first.chunkId()).isEqualTo("c-int");
        assertThat((double) first.scores().get("similarity")).isCloseTo(1.0, org.assertj.core.api.Assertions.offset(1e-4));
    }

    @Test
    void ranksAndSimilarity() {
        var hits = retriever.search(QUERY, 7, RESTRICTED);
        assertThat(hits).hasSize(7);
        for (int i = 0; i < hits.size(); i++) {
            assertThat(hits.get(i).scores().get("vectorRank")).isEqualTo(i + 1);
            assertThat((double) hits.get(i).scores().get("similarity")).isBetween(-1.0, 1.0);
            if (i > 0) {
                assertThat(hits.get(i).score()).isLessThanOrEqualTo(hits.get(i - 1).score());
            }
        }
        assertThat(hits.getFirst().text()).isEqualTo(QUERY);
        assertThat(hits.getFirst().sectionPath()).containsExactly("2");
        assertThat(hits.getFirst().docTitle()).isEqualTo("Title eb-a");
    }

    @Test
    void missingAliasReturnsNoHits() {
        admin.delete(admin.aliasInfo("t_vec-chunks").orElseThrow().index());
        assertThat(retriever.search(QUERY, 5, RESTRICTED)).isEmpty();
    }
}
