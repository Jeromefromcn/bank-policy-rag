package rag.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Offline sweep of the citation-overlap threshold against the judge's citation verdicts (spec 03 §5, §9). Reads a
 * generation run's {@code answers.json}, uses {@code dev} items only, and spends no tokens.
 */
public final class OverlapSweep {

    public record Row(double threshold, int flagged, int flaggedUnsupported, int flaggedSupported, int unsupportedTotal, int supportedTotal) {}

    private OverlapSweep() {}

    public static List<Row> run(Path runDir) {
        List<double[]> sentences = new ArrayList<>();   // {overlap, 1 = unsupported}
        for (JsonNode item : LabelSheet.read(runDir).path("items")) {
            if (!item.path("split").asString("").equals("dev") || !item.path("kind").asString("").equals("ANSWERABLE")) {
                continue;
            }
            for (JsonNode s : item.path("sentences")) {
                String verdict = s.path("verdict").asString("");
                if (verdict.equals("SUPPORTED") || verdict.equals("UNSUPPORTED")) {
                    sentences.add(new double[] {s.path("overlap").asDouble(), verdict.equals("UNSUPPORTED") ? 1 : 0});
                }
            }
        }
        int unsupported = (int) sentences.stream().filter(s -> s[1] == 1).count();
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i <= 100; i++) {
            double t = i / 100.0;
            int flagged = 0;
            int flaggedUnsupported = 0;
            for (double[] s : sentences) {
                if (s[0] < t) {
                    flagged++;
                    flaggedUnsupported += (int) s[1];
                }
            }
            rows.add(new Row(t, flagged, flaggedUnsupported, flagged - flaggedUnsupported, unsupported, sentences.size() - unsupported));
        }
        write(runDir, rows);
        return rows;
    }

    private static void write(Path runDir, List<Row> rows) {
        StringBuilder md = new StringBuilder("# Citation overlap threshold sweep (dev)\n\n");
        md.append("| threshold | flagged | flagged unsupported | flagged supported | unsupported total | supported total |\n|---|---|---|---|---|---|\n");
        for (Row r : rows) {
            md.append(String.format("| %.2f | %d | %d | %d | %d | %d |%n", r.threshold(), r.flagged(), r.flaggedUnsupported(), r.flaggedSupported(),
                    r.unsupportedTotal(), r.supportedTotal()));
        }
        try {
            Files.writeString(runDir.resolve("overlap-sweep.md"), md.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
