package rag.app.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import rag.app.RagProperties;
import rag.core.model.Clearance;

class ApiKeyAuthFilterTest {

    @Test
    void resolvesConfiguredKey() {
        var filter = new ApiKeyAuthFilter(List.of(new RagProperties.ApiKey("k1", "alice", Clearance.INTERNAL, Set.of("admin"))));
        assertThat(filter.resolve("k1")).hasValue(new Principal("alice", Clearance.INTERNAL, Set.of("admin")));
        assertThat(filter.resolve("k2")).isEmpty();
        assertThat(filter.resolve(null)).isEmpty();
    }

    @Test
    void blankConfiguredKeysNeverMatch() {
        var filter = new ApiKeyAuthFilter(List.of(
                new RagProperties.ApiKey("", "ghost", Clearance.RESTRICTED, Set.of()),
                new RagProperties.ApiKey("k1", "alice", Clearance.PUBLIC, Set.of())));
        assertThat(filter.resolve("")).isEmpty();
    }

    @Test
    void noUsableKeyFailsStartup() {
        assertThatThrownBy(() -> new ApiKeyAuthFilter(List.of(new RagProperties.ApiKey(" ", "ghost", Clearance.PUBLIC, Set.of()))))
                .hasMessageContaining("api-keys");
    }
}
