package rag.core.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TokenCounterTest {

    private final TokenCounter tc = TokenCounter.cl100k();

    private static String text(int sentences) {
        return IntStream.range(0, sentences)
                .mapToObj(i -> "The payments service fails over to site " + i + " within two hours.")
                .collect(Collectors.joining(" "));
    }

    @Test
    void truncateLeavesShortTextUnchanged() {
        String s = "Ten tokens or so of short self-made text.";
        assertThat(tc.truncate(s, 256)).isEqualTo(s);
    }

    @Test
    void truncateCutsToExactlyMaxTokens() {
        String s = text(60);
        assertThat(tc.count(s)).isGreaterThan(600);
        String cut = tc.truncate(s, 256);
        assertThat(tc.count(cut)).isEqualTo(256);
        assertThat(s).startsWith(cut);
    }

    @Test
    void truncateNeverSplitsAMultiByteCharacter() {
        String s = "é".repeat(400) + " 支付服务".repeat(100);
        String cut = tc.truncate(s, 101);
        assertThat(s).startsWith(cut);
        assertThat(tc.count(cut)).isLessThanOrEqualTo(101);
    }

    @Test
    void cl100kIsLoadedOnceAndShared() {
        // The encoding tables are large; one copy per caller (one per Spring context in tests) exhausts the heap.
        org.assertj.core.api.Assertions.assertThat(TokenCounter.cl100k()).isSameAs(TokenCounter.cl100k());
    }
}
