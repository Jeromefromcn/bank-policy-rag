package rag.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rag.core.model.Clearance;

class InjectionSetTest {

    @TempDir Path tmp;

    List<InjectionItem> load(String yaml) throws IOException {
        Path file = tmp.resolve("injection.yaml");
        Files.writeString(file, yaml);
        return InjectionSet.load(file, Set.of("eb-a", "eb-b"));
    }

    @Test
    void rejectsUnknownClearance() {
        assertThatThrownBy(() -> load("""
                - id: x
                  question: q
                  clearance: secret
                  expectedStatus: ANSWERED
                """))
                .hasMessageContaining("clearance");
    }

    @Test
    void rejectsUnknownExpectedStatus() {
        assertThatThrownBy(() -> load("""
                - id: x
                  question: q
                  clearance: public
                  expectedStatus: MAYBE
                """))
                .hasMessageContaining("expectedStatus");
    }

    @Test
    void rejectsUnknownForbiddenDocId() {
        assertThatThrownBy(() -> load("""
                - id: x
                  question: q
                  clearance: public
                  expectedStatus: ANSWERED
                  mustNotCiteDocIds: [eb-secret]
                """))
                .hasMessageContaining("eb-secret");
    }

    @Test
    void rejectsDuplicateId() {
        assertThatThrownBy(() -> load("""
                - id: x
                  question: q
                  clearance: public
                  expectedStatus: ANSWERED
                - id: x
                  question: q2
                  clearance: public
                  expectedStatus: REFUSED
                """))
                .hasMessageContaining("duplicate");
    }

    @Test
    void parsesAValidItem() throws IOException {
        var items = load("""
                - id: inj001
                  question: Who approves a vendor?
                  clearance: internal
                  expectedStatus: ANSWERED
                  mustNotContain: [reset.invalid, maintenance mode]
                  mustNotCiteDocIds: [eb-a]
                """);
        assertThat(items).singleElement().satisfies(i -> {
            assertThat(i.clearance()).isEqualTo(Clearance.INTERNAL);
            assertThat(i.expectedStatus()).isEqualTo("ANSWERED");
            assertThat(i.mustNotContain()).containsExactly("reset.invalid", "maintenance mode");
            assertThat(i.mustNotCiteDocIds()).containsExactly("eb-a");
        });
    }
}
