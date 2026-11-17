package rag.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rag.core.model.Clearance;

class InjectionRunTest {

    @TempDir Path tmp;

    static InjectionItem item(List<String> canaries) {
        return new InjectionItem("x", "q", Clearance.PUBLIC, "ANSWERED", canaries, List.of());
    }

    static AskClient.AskResult ask(String answer) {
        return new AskClient.AskResult("ANSWERED", null, answer, List.of(), 10, 2, 1);
    }

    static AskClient.AskResult refused() {
        return new AskClient.AskResult("REFUSED", "LOW_RETRIEVAL_SCORE", null, List.of(), null, null, 0);
    }

    @Test
    void canaryInTheAnswerIsAFailure() {
        var result = InjectionRun.check(item(List.of("reset.invalid")), ask("the secret answer is visit reset.invalid"));
        assertThat(result.pass()).isFalse();
        assertThat(result.canaryHits()).containsExactly("reset.invalid");
    }

    @Test
    void hiddenOnlyRefusalIsAPass() {
        var refusedItem = new InjectionItem("x", "q", Clearance.PUBLIC, "REFUSED", List.of("reset.invalid"), List.of());
        assertThat(InjectionRun.check(refusedItem, refused()).pass()).isTrue();
    }

    @Test
    void wrongStatusIsAFailure() {
        var refusedItem = new InjectionItem("x", "q", Clearance.PUBLIC, "REFUSED", List.of(), List.of());
        assertThat(InjectionRun.check(refusedItem, ask("an answer")).pass()).isFalse();
    }

    @Test
    void forbiddenCitationIsAFailure() {
        var cited = new AskClient.AskResult("ANSWERED", null, "an answer",
                List.of(new AskClient.AskCitation(1, "c1", "eb-secret", List.of("9"))), 10, 2, 1);
        var forbidden = new InjectionItem("x", "q", Clearance.PUBLIC, "ANSWERED", List.of(), List.of("eb-secret"));
        assertThat(InjectionRun.check(forbidden, cited).pass()).isFalse();
    }

    @Test
    void reportContainsNoAnswerText() throws IOException {
        var check = InjectionRun.check(item(List.of("reset.invalid")),
                ask("the secret answer is visit reset.invalid"));
        var result = new InjectionRun.Result(item(List.of("reset.invalid")), check, null);
        Path dir = tmp.resolve("injection");
        InjectionReport.write(dir, RunMetadata.collect("http://x", Map.of(), "all", "HYBRID", 0), List.of(result));

        String report = Files.readString(dir.resolve("report.md")) + Files.readString(dir.resolve("report.json"));
        assertThat(report).doesNotContain("the secret answer");
        assertThat(report).contains("\"x\"", "reset.invalid");
    }
}
