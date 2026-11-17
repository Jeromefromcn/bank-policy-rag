package rag.core.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import rag.core.model.Clearance;

class EvalScopeTest {

    static final Set<String> EXAMPLE_BANK = Set.of("eb-a", "adv-x");

    static GoldenItem item(String id, ExpectedSource... sources) {
        return new GoldenItem(id, "q", sources.length == 0 ? "unanswerable" : "factual", List.of(sources), List.of(),
                Clearance.PUBLIC, "dev");
    }

    @Test
    void exampleBankSourcesAreInScope() {
        assertThat(EvalScope.inExampleBank(item("g1", new ExpectedSource("eb-a", "1")), EXAMPLE_BANK)).isTrue();
    }

    @Test
    void spmSourcesAreOutOfScope() {
        assertThat(EvalScope.inExampleBank(item("g2", new ExpectedSource("spm-or-2", "3.2")), EXAMPLE_BANK)).isFalse();
    }

    @Test
    void mixedSourcesAreOutOfScope() {
        assertThat(EvalScope.inExampleBank(item("g3", new ExpectedSource("eb-a", "1"),
                new ExpectedSource("spm-or-2", "3.2")), EXAMPLE_BANK)).isFalse();
    }

    @Test
    void unanswerableItemsAreInScope() {
        assertThat(EvalScope.inExampleBank(item("g4"), EXAMPLE_BANK)).isTrue();
    }
}
