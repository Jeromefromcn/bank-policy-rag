package rag.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import rag.core.model.Clearance;

class KeySetTest {

    static final Map<String, String> ENV = Map.of("RAG_KEY_DEV", "k-res", "RAG_KEY_INTERNAL", "k-int", "RAG_KEY_PUBLIC", "k-pub");

    @Test
    void mapsClearanceToKey() {
        var keys = KeySet.fromEnv(ENV);
        assertThat(keys.forClearance(Clearance.RESTRICTED)).isEqualTo("k-res");
        assertThat(keys.forClearance(Clearance.INTERNAL)).isEqualTo("k-int");
        assertThat(keys.forClearance(Clearance.PUBLIC)).isEqualTo("k-pub");
    }

    @Test
    void belowIsOneLevelDown() {
        assertThat(KeySet.below(Clearance.RESTRICTED)).isEqualTo(Clearance.INTERNAL);
        assertThat(KeySet.below(Clearance.INTERNAL)).isEqualTo(Clearance.PUBLIC);
        assertThatThrownBy(() -> KeySet.below(Clearance.PUBLIC)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingKeyNamesTheVariable() {
        var keys = KeySet.fromEnv(Map.of("RAG_KEY_DEV", "k-res"));
        assertThatThrownBy(() -> keys.forClearance(Clearance.PUBLIC)).hasMessageContaining("RAG_KEY_PUBLIC");
        assertThatThrownBy(() -> KeySet.fromEnv(Map.of())).hasMessageContaining("RAG_KEY_DEV");
    }

    @Test
    void requireReportsEveryMissingVariableAtOnce() {
        var keys = KeySet.fromEnv(Map.of("RAG_KEY_DEV", "k-res"));

        assertThatThrownBy(() -> keys.require(java.util.Set.of(Clearance.PUBLIC, Clearance.INTERNAL, Clearance.RESTRICTED)))
                .hasMessageContaining("RAG_KEY_PUBLIC").hasMessageContaining("RAG_KEY_INTERNAL");
        keys.require(java.util.Set.of(Clearance.RESTRICTED));
    }

    @Test
    void neededClearancesFollowTheItems() {
        var items = java.util.List.of(
                new rag.core.eval.GoldenItem("a", "q", "factual", java.util.List.of(), java.util.List.of(), Clearance.PUBLIC, "dev"),
                new rag.core.eval.GoldenItem("b", "q", "restricted", java.util.List.of(), java.util.List.of(), Clearance.INTERNAL, "dev"),
                new rag.core.eval.GoldenItem("c", "q", "unanswerable", java.util.List.of(), java.util.List.of(), Clearance.PUBLIC, "test"));

        assertThat(KeySet.needed(items, "dev")).containsExactlyInAnyOrder(Clearance.PUBLIC, Clearance.INTERNAL);
        assertThat(KeySet.needed(items, "all")).containsExactlyInAnyOrder(Clearance.PUBLIC, Clearance.INTERNAL, Clearance.RESTRICTED);
    }
}
