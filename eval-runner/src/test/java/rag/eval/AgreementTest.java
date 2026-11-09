package rag.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rag.eval.Agreement.LabeledPoint;

class AgreementTest {

    @TempDir Path tmp;

    static List<LabeledPoint> table(int yy, int yn, int ny, int nn) {
        List<LabeledPoint> out = new ArrayList<>();
        int i = 0;
        for (int k = 0; k < yy; k++) out.add(new LabeledPoint("g" + i++, 0, true, true));
        for (int k = 0; k < yn; k++) out.add(new LabeledPoint("g" + i++, 0, true, false));
        for (int k = 0; k < ny; k++) out.add(new LabeledPoint("g" + i++, 0, false, true));
        for (int k = 0; k < nn; k++) out.add(new LabeledPoint("g" + i++, 0, false, false));
        return out;
    }

    @Test
    void kappaOnKnownTable() {
        // human-yes/judge-yes 10, human-yes/judge-no 2, human-no/judge-yes 3, both no 5: po .75, pe .53, kappa .468
        var r = Agreement.compute(table(10, 2, 3, 5));

        assertThat(r.n()).isEqualTo(20);
        assertThat(r.agree()).isEqualTo(15);
        assertThat(r.agreement()).isCloseTo(0.75, offset(1e-9));
        assertThat(r.kappa()).isCloseTo(0.4680851, offset(1e-6));
    }

    @Test
    void allAgreeGivesOne() {
        assertThat(Agreement.compute(table(12, 0, 0, 8)).kappa()).isCloseTo(1.0, offset(1e-9));
    }

    @Test
    void degenerateMarginsDoNotDivideByZero() {
        var r = Agreement.compute(table(20, 0, 0, 0));

        assertThat(r.agreement()).isCloseTo(1.0, offset(1e-9));
        assertThat(r.kappa()).isNull();
    }

    Path answers() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("run"));
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            items.append(i > 0 ? "," : "").append("{\"id\":\"g").append(i).append("\",\"kind\":\"ANSWERABLE\",\"pointVerdicts\":[\"SUPPORTED\"]}");
        }
        Files.writeString(dir.resolve("answers.json"), "{\"items\":[" + items + "]}");
        return dir;
    }

    String labels(int count) {
        StringBuilder y = new StringBuilder();
        for (int i = 0; i < count; i++) {
            y.append("- {itemId: g").append(i).append(", pointIndex: 0, humanVerdict: ").append(i % 2 == 0 ? "yes" : "no").append("}\n");
        }
        return y.toString();
    }

    @Test
    void loadJoinsLabelsWithJudgeVerdicts() throws Exception {
        Path labels = Files.writeString(tmp.resolve("labels.yaml"), labels(20));

        var points = Agreement.load(labels, answers(), 20);

        assertThat(points).hasSize(20);
        assertThat(points.get(1).human()).isFalse();
        assertThat(points.get(1).judge()).isTrue();
    }

    @Test
    void loadFailsClearlyOnMissingFile() throws Exception {
        assertThatThrownBy(() -> Agreement.load(tmp.resolve("absent.yaml"), answers(), 20)).hasMessageContaining("absent.yaml");
    }

    @Test
    void loadFailsOnTooFewEntries() throws Exception {
        Path labels = Files.writeString(tmp.resolve("labels.yaml"), labels(19));

        assertThatThrownBy(() -> Agreement.load(labels, answers(), 20)).hasMessageContaining("20");
    }

    @Test
    void loadFailsOnUnknownPoint() throws Exception {
        Path labels = Files.writeString(tmp.resolve("labels.yaml"), labels(20) + "- {itemId: nope, pointIndex: 0, humanVerdict: yes}\n");

        assertThatThrownBy(() -> Agreement.load(labels, answers(), 20)).hasMessageContaining("nope");
    }

    @Test
    void loadFailsOnBadVerdictWord() throws Exception {
        Path labels = Files.writeString(tmp.resolve("labels.yaml"), labels(19) + "- {itemId: g19, pointIndex: 0, humanVerdict: maybe}\n");

        assertThatThrownBy(() -> Agreement.load(labels, answers(), 20)).hasMessageContaining("maybe");
    }
}
