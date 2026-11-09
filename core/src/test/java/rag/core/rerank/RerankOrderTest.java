package rag.core.rerank;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RerankOrderTest {

    @Test
    void orderSortsDescendingWithStableTies() {
        assertThat(RerankOrder.order(List.of(0.1, 0.9, 0.9, -2.0))).containsExactly(1, 2, 0, 3);
    }

    @Test
    void orderOfEmptyIsEmpty() {
        assertThat(RerankOrder.order(List.of())).isEmpty();
    }

    @Test
    void sigmoidMapsLogits() {
        assertThat(RerankOrder.sigmoid(0)).isEqualTo(0.5);
        assertThat(RerankOrder.sigmoid(10)).isGreaterThan(0.9999);
        assertThat(RerankOrder.sigmoid(-10)).isLessThan(0.0001);
        double prev = -1;
        for (double x = -12; x <= 12; x += 0.5) {
            double y = RerankOrder.sigmoid(x);
            assertThat(y).isGreaterThan(prev);
            prev = y;
        }
    }
}
