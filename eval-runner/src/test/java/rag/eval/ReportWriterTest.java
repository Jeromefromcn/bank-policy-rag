package rag.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rag.core.eval.ExpectedSource;
import rag.core.eval.GoldenItem;
import rag.core.eval.ItemScores;
import rag.core.model.Clearance;

class ReportWriterTest {

    @TempDir Path dir;

    static GoldenItem item(String id, String category, String split) {
        return new GoldenItem(id, "Secret question text " + id + "?", category, List.of(new ExpectedSource("d", "1")), List.of(),
                Clearance.PUBLIC, split);
    }

    static RunMetadata meta() {
        return new RunMetadata("2026-11-08T00:00:00Z", "abc1234", false, "aarch64", 4, "http://x",
                Map.of("chunkerVersion", "fixed-512-64-cl100k"), "all", 1);
    }

    @Test
    void markdownHasSplitTablesAndNoQuestionText() throws Exception {
        var items = List.of(
                new ItemResult(item("g1", "factual", "test"), new ItemScores(1, 1, 1, 1, 1), 10),
                new ItemResult(item("g2", "exact-term", "dev"), new ItemScores(0, 0.5, 0.125, 0.3, 8), 20));
        new ReportWriter().write(dir, meta(), items);
        String md = Files.readString(dir.resolve("report.md"));
        assertThat(md).contains("| all |").contains("| exact-term |").contains("| factual |")
                .contains("fixed-512-64-cl100k").contains("abc1234")
                .doesNotContain("Secret question text");
        assertThat(md.indexOf("### test")).isPositive().isLessThan(md.indexOf("### dev"));
        assertThat(md).contains("| g2 | exact-term | dev | 8 | 0.000 |");
        assertThat(md).contains("| all | 1 | 1.000 | 1.000 | 1.000 | 1.000 |");
        assertThat(Files.readString(dir.resolve("report.json"))).contains("\"recall5\"").contains("\"g1\"");
    }

    @Test
    void missingRankShowsDash() throws Exception {
        new ReportWriter().write(dir, meta(), List.of(new ItemResult(item("g9", "factual", "test"), new ItemScores(0, 0, 0, 0, null), 5)));
        assertThat(Files.readString(dir.resolve("report.md"))).contains("| g9 | factual | test | - | 0.000 |");
    }

    @Test
    void percentilesNearestRank() {
        List<Long> latencies = LongStream.rangeClosed(1, 10).map(i -> i * 10).boxed().toList();
        assertThat(ReportWriter.percentile(latencies, 50)).isEqualTo(50);
        assertThat(ReportWriter.percentile(latencies, 95)).isEqualTo(100);
        assertThat(ReportWriter.percentile(List.of(7L), 95)).isEqualTo(7);
    }
}
