package rag.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rag.core.eval.ExpectedSource;
import rag.core.eval.GoldenItem;
import rag.core.model.Clearance;

class MultiTurnSetTest {

    static final Map<String, GoldenItem> GOLDEN = Map.of(
            "g1", new GoldenItem("g1", "Turn one question?", "factual", List.of(new ExpectedSource("eb-b", "2")), List.of(),
                    Clearance.PUBLIC, "dev"),
            "g2", new GoldenItem("g2", "Standalone question?", "factual", List.of(new ExpectedSource("eb-a", "1")), List.of(),
                    Clearance.INTERNAL, "dev"));

    @TempDir Path dir;

    Path yaml(String text) throws Exception {
        Path f = dir.resolve("multi-turn.yaml");
        Files.writeString(f, text);
        return f;
    }

    @Test
    void loadsItemAndDefaultsExpectedSourcesFromStandalone() throws Exception {
        var items = MultiTurnSet.load(yaml("""
                - id: m1
                  split: dev
                  turns: [g1]
                  followUp: "And that one?"
                  standaloneGoldenId: g2
                  minClearance: internal
                """), GOLDEN);
        assertThat(items).hasSize(1);
        var m = items.getFirst();
        assertThat(m.id()).isEqualTo("m1");
        assertThat(m.turns()).containsExactly("g1");
        assertThat(m.followUp()).isEqualTo("And that one?");
        assertThat(m.standaloneGoldenId()).isEqualTo("g2");
        assertThat(m.expectedSources()).containsExactly(new ExpectedSource("eb-a", "1"));
        assertThat(m.minClearance()).isEqualTo(Clearance.INTERNAL);
    }

    @Test
    void explicitExpectedSourcesWin() throws Exception {
        var m = MultiTurnSet.load(yaml("""
                - id: m1
                  split: dev
                  turns: [g1]
                  followUp: "And that one?"
                  standaloneGoldenId: g2
                  expectedSources: [{docId: eb-c, section: "3"}]
                  minClearance: internal
                """), GOLDEN).getFirst();
        assertThat(m.expectedSources()).containsExactly(new ExpectedSource("eb-c", "3"));
    }

    @Test
    void rejectsUnknownGoldenId() throws Exception {
        assertThatThrownBy(() -> MultiTurnSet.load(yaml("""
                - id: m1
                  split: dev
                  turns: [g9]
                  followUp: "x?"
                  standaloneGoldenId: g2
                  minClearance: internal
                """), GOLDEN)).hasMessageContaining("m1").hasMessageContaining("g9");
        assertThatThrownBy(() -> MultiTurnSet.load(yaml("""
                - id: m2
                  split: dev
                  turns: [g1]
                  followUp: "x?"
                  standaloneGoldenId: g8
                  minClearance: internal
                """), GOLDEN)).hasMessageContaining("m2").hasMessageContaining("g8");
    }

    @Test
    void rejectsMissingFollowUp() throws Exception {
        assertThatThrownBy(() -> MultiTurnSet.load(yaml("""
                - id: m1
                  split: dev
                  turns: [g1]
                  standaloneGoldenId: g2
                  minClearance: internal
                """), GOLDEN)).hasMessageContaining("m1").hasMessageContaining("followUp");
    }

    @Test
    void rejectsEmptyTurnsUnknownSplitAndDuplicateIds() throws Exception {
        assertThatThrownBy(() -> MultiTurnSet.load(yaml("""
                - {id: m1, split: dev, turns: [], followUp: "x?", standaloneGoldenId: g2, minClearance: internal}
                """), GOLDEN)).hasMessageContaining("turns");
        assertThatThrownBy(() -> MultiTurnSet.load(yaml("""
                - {id: m1, split: train, turns: [g1], followUp: "x?", standaloneGoldenId: g2, minClearance: internal}
                """), GOLDEN)).hasMessageContaining("split");
        assertThatThrownBy(() -> MultiTurnSet.load(yaml("""
                - {id: m1, split: dev, turns: [g1], followUp: "x?", standaloneGoldenId: g2, minClearance: internal}
                - {id: m1, split: dev, turns: [g1], followUp: "y?", standaloneGoldenId: g2, minClearance: internal}
                """), GOLDEN)).hasMessageContaining("duplicate");
    }
}
