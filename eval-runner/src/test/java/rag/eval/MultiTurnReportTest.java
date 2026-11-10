package rag.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rag.core.eval.ItemScores;

class MultiTurnReportTest {

    @TempDir Path dir;

    static ItemScores scores(double r5, double rr) {
        return new ItemScores(r5, r5, rr, r5, rr > 0 ? 1 : null);
    }

    static RunMetadata meta() {
        return new RunMetadata("2026-11-10T00:00:00Z", "abc", false, "aarch64", 4, "http://x", Map.of("rewritePrompt", "rewrite.v1"),
                "dev", "HYBRID", 0);
    }

    @Test
    void reportHasIdsAndScoresOnlyAndRewritesGoToJsonl() throws Exception {
        var ok = new MultiTurnRun.Outcome(MultiTurnRunTest.item("m1", "dev"), scores(0.0, 0.0), scores(1.0, 1.0), scores(1.0, 0.5),
                "SECRET REWRITTEN QUERY", null, 30L, 200, 20);
        var failed = new MultiTurnRun.Outcome(MultiTurnRunTest.item("m2", "dev"), scores(0.0, 0.0), null, scores(1.0, 1.0),
                null, "HTTP 503", 0L, null, null);
        MultiTurnReport.write(dir, meta(), List.of(ok, failed));

        String md = Files.readString(dir.resolve("report.md"));
        assertThat(md).contains("m1").contains("m2").contains("raw").contains("rewrite").contains("standalone");
        assertThat(md).doesNotContain(MultiTurnRunTest.FOLLOW_UP).doesNotContain("SECRET REWRITTEN QUERY");
        // rewrite: m1 scored 1.0, m2 failed and counts as 0 → mean 0.5 over n=2
        assertThat(md).contains("| rewrite | 2 | 0.500 | 0.500 |");
        assertThat(md).contains("| raw | 2 | 0.000 | 0.000 |");
        assertThat(md).contains("| standalone | 2 | 1.000 | 0.750 |");
        assertThat(md).contains("Failures: m2");

        List<String> lines = Files.readAllLines(dir.resolve("rewrites.jsonl"));
        assertThat(lines).hasSize(2);
        assertThat(lines.getFirst()).contains("\"id\":\"m1\"").contains("SECRET REWRITTEN QUERY");
        assertThat(Files.readString(dir.resolve("report.json"))).doesNotContain("SECRET REWRITTEN QUERY")
                .doesNotContain(MultiTurnRunTest.FOLLOW_UP);
    }
}
