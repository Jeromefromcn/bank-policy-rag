package rag.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

class LabelSheetTest {

    @TempDir Path tmp;

    static final List<String> CATEGORIES = List.of("factual", "exact-term", "multi-hop", "restricted", "factual");

    /** Writes an answers.json with 15 answered items x 2 points; verdicts alternate, one point is ERROR. */
    Path runDir() throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", "g" + i);
            row.put("category", CATEGORIES.get(i % CATEGORIES.size()));
            row.put("question", "Question " + i + "?");
            row.put("kind", "ANSWERABLE");
            row.put("points", List.of("point A " + i, "point B " + i));
            row.put("sources", List.of("source text " + i));
            row.put("ask", Map.of("status", "ANSWERED", "answer", "Answer text " + i + " [1]."));
            row.put("pointVerdicts", List.of(i % 2 == 0 ? "SUPPORTED" : "UNSUPPORTED", i == 3 ? "ERROR" : "SUPPORTED"));
            row.put("sentences", List.of());
            items.add(row);
        }
        Path dir = Files.createDirectories(tmp.resolve("run"));
        Files.writeString(dir.resolve("answers.json"), JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build()
                .writeValueAsString(Map.of("items", items)));
        return dir;
    }

    @Test
    void exportsTwentyPoints() throws Exception {
        var sample = LabelSheet.export(runDir(), 7, 20, tmp.resolve("sheet.md"));

        assertThat(sample).hasSize(20);
        assertThat(sample.stream().map(p -> p.itemId() + "/" + p.pointIndex()).collect(Collectors.toSet())).hasSize(20);
    }

    @Test
    void sameSeedSameSample() throws Exception {
        Path run = runDir();
        assertThat(LabelSheet.export(run, 7, 20, tmp.resolve("a.md"))).isEqualTo(LabelSheet.export(run, 7, 20, tmp.resolve("b.md")));
    }

    @Test
    void sheetOmitsJudgeVerdict() throws Exception {
        Path sheet = tmp.resolve("sheet.md");
        LabelSheet.export(runDir(), 7, 20, sheet);

        assertThat(Files.readString(sheet)).doesNotContain("SUPPORTED").doesNotContain("UNSUPPORTED").doesNotContainIgnoringCase("judge")
                .contains("Answer text").contains("source text").contains("point A");
    }

    @Test
    void stratifiedByCategoryAndVerdict() throws Exception {
        var sample = LabelSheet.export(runDir(), 7, 20, tmp.resolve("sheet.md"));

        Set<String> categories = sample.stream().map(LabelSheet.SampledPoint::category).collect(Collectors.toSet());
        Set<Boolean> verdicts = sample.stream().map(LabelSheet.SampledPoint::judgeSupported).collect(Collectors.toSet());
        assertThat(categories.size()).isGreaterThanOrEqualTo(4);
        assertThat(verdicts).containsExactlyInAnyOrder(true, false);
    }

    @Test
    void excludesErrorVerdicts() throws Exception {
        var sample = LabelSheet.export(runDir(), 7, 29, tmp.resolve("sheet.md"));

        assertThat(sample).hasSize(29);
        assertThat(sample).noneMatch(p -> p.itemId().equals("g3") && p.pointIndex() == 1);
    }
}
