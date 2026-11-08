package rag.core.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rag.core.corpus.CorpusLoader;
import rag.core.model.Clearance;

class GoldenSetTest {

    @TempDir Path tmp;

    static final String ITEM = """
            - id: g001
              question: "What is X?"
              category: factual
              expectedSources: [{docId: eb-a, section: "3"}]
              expectedPoints: ["x"]
              minClearance: internal
              split: dev
            """;

    Path write(String yaml) throws Exception {
        return Files.writeString(tmp.resolve("golden.yaml"), yaml);
    }

    @Test
    void loadsRealGoldenSet() {
        var items = GoldenSet.load(Path.of("../eval/golden-set.yaml"), CorpusLoader.knownDocIds(Path.of("../corpus")));
        assertThat(items).hasSize(42);
        assertThat(items).filteredOn(i -> i.split().equals("test")).hasSize(12);
        assertThat(items).filteredOn(i -> i.category().equals("unanswerable")).allSatisfy(i -> assertThat(i.expectedSources()).isEmpty());
    }

    @Test
    void parsesFields() throws Exception {
        var item = GoldenSet.load(write(ITEM), Set.of("eb-a")).getFirst();
        assertThat(item.id()).isEqualTo("g001");
        assertThat(item.expectedSources()).containsExactly(new ExpectedSource("eb-a", "3"));
        assertThat(item.minClearance()).isEqualTo(Clearance.INTERNAL);
        assertThat(item.expectedPoints()).containsExactly("x");
    }

    @Test
    void rejectsUnknownDocId() throws Exception {
        assertThatThrownBy(() -> GoldenSet.load(write(ITEM.replace("eb-a", "eb-typo")), Set.of("eb-a")))
                .hasMessageContaining("g001").hasMessageContaining("eb-typo");
    }

    @Test
    void rejectsUnknownCategory() throws Exception {
        assertThatThrownBy(() -> GoldenSet.load(write(ITEM.replace("factual", "trivia")), Set.of("eb-a")))
                .hasMessageContaining("g001").hasMessageContaining("trivia");
    }

    @Test
    void rejectsMissingSplit() throws Exception {
        assertThatThrownBy(() -> GoldenSet.load(write(ITEM.replace("  split: dev\n", "")), Set.of("eb-a")))
                .hasMessageContaining("g001").hasMessageContaining("split");
    }

    @Test
    void rejectsDuplicateIds() throws Exception {
        assertThatThrownBy(() -> GoldenSet.load(write(ITEM + ITEM), Set.of("eb-a"))).hasMessageContaining("duplicate");
    }

    @Test
    void answerableItemNeedsSources() throws Exception {
        assertThatThrownBy(() -> GoldenSet.load(write(ITEM.replace("[{docId: eb-a, section: \"3\"}]", "[]")), Set.of("eb-a")))
                .hasMessageContaining("g001").hasMessageContaining("expectedSources");
    }
}
