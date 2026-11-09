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
}
