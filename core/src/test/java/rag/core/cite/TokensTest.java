package rag.core.cite;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokensTest {

    @Test
    void inflectionsOfTheSameWordShareAStem() {
        assertThat(Tokens.of("rate")).isEqualTo(Tokens.of("rates"));
        assertThat(Tokens.of("approve")).isEqualTo(Tokens.of("approves"));
        assertThat(Tokens.of("approve")).isEqualTo(Tokens.of("approved"));
        assertThat(Tokens.of("limit")).isEqualTo(Tokens.of("limits"));
        assertThat(Tokens.of("test")).isEqualTo(Tokens.of("testing"));
    }

    @Test
    void stopWordsAreDropped() {
        assertThat(Tokens.of("The board of the bank")).containsExactly("board", "bank");
    }
}
