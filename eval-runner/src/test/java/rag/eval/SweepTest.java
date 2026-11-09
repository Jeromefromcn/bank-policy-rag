package rag.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import rag.core.eval.GoldenItem;
import rag.core.model.Clearance;
import rag.eval.Sweep.Kind;
import rag.eval.Sweep.Observation;

class SweepTest {

    static final KeySet KEYS = KeySet.fromEnv(KeySetTest.ENV);

    static GoldenItem item(String id, String category, Clearance min, String split) {
        return new GoldenItem(id, "Question " + id + "?", category, List.of(), List.of(), min, split);
    }

    static Sweep.Row at(Sweep.Table t, double threshold) {
        return t.rows().stream().filter(r -> Math.abs(r.threshold() - threshold) < 1e-9).findFirst().orElseThrow();
    }

    @Test
    void refusedWhenBestSimilarityBelowThreshold() {
        var obs = List.of(
                new Observation("a1", "factual", Kind.ANSWERABLE, 0.9), new Observation("a2", "exact-term", Kind.ANSWERABLE, 0.4),
                new Observation("u1", "unanswerable", Kind.UNANSWERABLE, 0.2), new Observation("u2", "unanswerable", Kind.UNANSWERABLE, 0.6),
                new Observation("r1", "restricted", Kind.BELOW_CLEARANCE, 0.3));
        var t = Sweep.table(obs);

        var low = at(t, 0.30);
        assertThat(low.falseRefusalRate()).isEqualTo(0.0);
        assertThat(low.unanswerableRefusedRate()).isEqualTo(0.5);
        assertThat(low.restrictedRefusedRate()).isEqualTo(0.0);

        var mid = at(t, 0.50);
        assertThat(mid.falseRefusalRate()).isEqualTo(0.5);
        assertThat(mid.falseRefusalByCategory()).containsEntry("exact-term", 1.0).containsEntry("factual", 0.0);
        assertThat(mid.unanswerableRefusedRate()).isEqualTo(0.5);
        assertThat(mid.restrictedRefusedRate()).isEqualTo(1.0);
        assertThat(t.nAnswerable()).isEqualTo(2);
        assertThat(t.nUnanswerable()).isEqualTo(2);
        assertThat(t.rows()).hasSize(101);
    }

    @Test
    void nullSimilarityCountsAsNotRefused() {
        var t = Sweep.table(List.of(new Observation("a1", "factual", Kind.ANSWERABLE, null)));

        assertThat(at(t, 1.0).falseRefusalRate()).isEqualTo(0.0);
        assertThat(t.noSignal()).isEqualTo(1);
    }

    @Test
    void usesDevItemsOnly() throws Exception {
        try (var stub = new StubServer()) {
            var items = List.of(item("d1", "factual", Clearance.INTERNAL, "dev"), item("t1", "factual", Clearance.INTERNAL, "test"));
            var obs = Sweep.collect(items, KEYS, stub.base(), "HYBRID");

            assertThat(obs).extracting(Observation::itemId).containsExactly("d1");
            assertThat(stub.searches()).isEqualTo(1);
        }
    }

    @Test
    void restrictedItemsRunWithKeyAtAndBelowMinClearance() throws Exception {
        try (var stub = new StubServer()) {
            var obs = Sweep.collect(List.of(item("r1", "restricted", Clearance.INTERNAL, "dev")), KEYS, stub.base(), "HYBRID");

            assertThat(stub.requests).extracting(StubServer.Request::apiKey).containsExactly("k-int", "k-pub");
            assertThat(obs).extracting(Observation::kind).containsExactly(Kind.ANSWERABLE, Kind.BELOW_CLEARANCE);
        }
    }

    @Test
    void unanswerableUsesRestrictedKey() throws Exception {
        try (var stub = new StubServer()) {
            var obs = Sweep.collect(List.of(item("u1", "unanswerable", Clearance.PUBLIC, "dev")), KEYS, stub.base(), "HYBRID");

            assertThat(stub.requests.getFirst().apiKey()).isEqualTo("k-res");
            assertThat(obs.getFirst().kind()).isEqualTo(Kind.UNANSWERABLE);
            assertThat(obs.getFirst().bestSimilarity()).isEqualTo(0.8);
        }
    }

    @Test
    void answerableItemsRunAtMinClearance() throws Exception {
        try (var stub = new StubServer()) {
            Sweep.collect(List.of(item("f1", "factual", Clearance.PUBLIC, "dev")), KEYS, stub.base(), "HYBRID");

            assertThat(stub.requests.getFirst().apiKey()).isEqualTo("k-pub");
        }
    }

    @Test
    void thresholdZeroRefusesNothingEvenWithNegativeSimilarity() {
        var t = Sweep.table(List.of(new Observation("a1", "factual", Kind.ANSWERABLE, -0.2)));

        assertThat(at(t, 0.0).falseRefusalRate()).isEqualTo(0.0);
        assertThat(at(t, 0.01).falseRefusalRate()).isEqualTo(1.0);
    }
}
