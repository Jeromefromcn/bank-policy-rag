package rag.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OverlapSweepTest {

    @TempDir Path tmp;

    static String item(String id, String split, String sentences) {
        return "{\"id\":\"" + id + "\",\"split\":\"" + split + "\",\"kind\":\"ANSWERABLE\",\"sentences\":[" + sentences + "]}";
    }

    static String s(double overlap, String verdict) {
        return "{\"text\":\"x\",\"overlap\":" + overlap + ",\"verdict\":\"" + verdict + "\"}";
    }

    Path run(String... items) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("run"));
        Files.writeString(dir.resolve("answers.json"), "{\"items\":[" + String.join(",", items) + "]}");
        return dir;
    }

    @Test
    void countsFlaggedAndCaughtUnsupportedSentencesPerThreshold() throws Exception {
        // dev: unsupported at overlap .2 and .6, supported at .9 and .5; test items must be ignored
        Path dir = run(item("a", "dev", String.join(",", s(0.2, "UNSUPPORTED"), s(0.6, "UNSUPPORTED"), s(0.9, "SUPPORTED"), s(0.5, "SUPPORTED"))),
                item("t", "test", s(0.1, "UNSUPPORTED")));

        var table = OverlapSweep.run(dir);

        var at50 = table.stream().filter(r -> Math.abs(r.threshold() - 0.50) < 1e-9).findFirst().orElseThrow();
        assertThat(at50.flagged()).isEqualTo(1);            // only .2 is below .50
        assertThat(at50.flaggedUnsupported()).isEqualTo(1);
        assertThat(at50.unsupportedTotal()).isEqualTo(2);
        var at70 = table.stream().filter(r -> Math.abs(r.threshold() - 0.70) < 1e-9).findFirst().orElseThrow();
        assertThat(at70.flagged()).isEqualTo(3);            // .2, .6, .5
        assertThat(at70.flaggedUnsupported()).isEqualTo(2);
        assertThat(Files.readString(dir.resolve("overlap-sweep.md"))).contains("| 0.70 |");
    }

    @Test
    void errorVerdictsAreExcluded() throws Exception {
        Path dir = run(item("a", "dev", s(0.1, "ERROR")));

        assertThat(OverlapSweep.run(dir).get(50).flagged()).isZero();
    }
}
