package rag.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import rag.core.eval.ExpectedSource;
import rag.core.eval.GoldenItem;
import rag.core.model.Clearance;

class CiGateTest {

    static final Set<String> EXAMPLE_BANK = Set.of("eb-a");

    static List<GoldenItem> items() {
        List<GoldenItem> out = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            out.add(new GoldenItem("eb" + i, "q", "factual",
                    List.of(new ExpectedSource("eb-a", "1")), List.of(), Clearance.INTERNAL, "dev"));
        }
        for (int i = 0; i < 12; i++) {
            out.add(new GoldenItem("spm" + i, "q", "factual",
                    List.of(new ExpectedSource("spm-or-2", "3")), List.of(), Clearance.PUBLIC, "dev"));
        }
        for (int i = 0; i < 6; i++) {
            out.add(new GoldenItem("un" + i, "q", "unanswerable", List.of(), List.of(), Clearance.RESTRICTED, "dev"));
        }
        return out;
    }

    static Baseline baseline(double recall, double mrr) {
        return new Baseline("example-bank", "c", "ch", "m", "HYBRID", 10, false, 30, 24, 6, 12,
                new Baseline.Overall(recall, mrr, 0.0, 24), Map.of(), "2026-11-17T00:00:00Z");
    }

    static CiGate.Result result(double recall, double mrr) {
        return new CiGate.Result(30, 24, 6, List.of(), recall, mrr, 0.0, Map.of());
    }

    @Test
    void scopeKeepsOnlyExampleBankAndCountsUnanswerable() {
        var scope = CiGate.scope(items(), EXAMPLE_BANK);
        assertThat(scope.included()).isEqualTo(30);
        assertThat(scope.scored()).isEqualTo(24);
        assertThat(scope.unanswerable()).isEqualTo(6);
        assertThat(scope.excludedIds()).hasSize(12);
    }

    @Test
    void gateFailsOnADropGreaterThanTwoHundredths() {
        assertThatThrownBy(() -> CiGate.assertNoRegression(result(0.77, 0.70), baseline(0.80, 0.70)))
                .hasMessageContaining("Recall@5").hasMessageContaining("0.030");
    }

    @Test
    void gatePassesAtExactlyTwoHundredths() {
        CiGate.assertNoRegression(result(0.78, 0.70), baseline(0.80, 0.70));
    }

    @Test
    void gateFailsOnAnMrrDrop() {
        assertThatThrownBy(() -> CiGate.assertNoRegression(result(0.80, 0.67), baseline(0.80, 0.70)))
                .hasMessageContaining("MRR");
    }

    @Test
    void missingBaselineFailsWithAWriteBaselineHint() {
        assertThatThrownBy(() -> Baseline.read(Path.of("missing.json")))
                .hasMessageContaining("ciWriteBaseline");
    }
}
