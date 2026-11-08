package rag.core.corpus;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rag.core.ids.Hashes;

class CorpusVersionTest {

    @TempDir Path tmp;

    @Test
    void versionIsStableTwelveHex() throws Exception {
        Path root = TestCorpus.create(tmp);
        assertThat(CorpusVersion.compute(root)).hasSize(12).matches("[0-9a-f]+").isEqualTo(CorpusVersion.compute(root));
    }

    @Test
    void versionChangesWhenMarkdownChanges() throws Exception {
        Path root = TestCorpus.create(tmp);
        String v1 = CorpusVersion.compute(root);
        Files.writeString(root.resolve("internal/eb-a.md"), "edited", java.nio.file.StandardOpenOption.APPEND);
        assertThat(CorpusVersion.compute(root)).isNotEqualTo(v1);
    }

    @Test
    void versionChangesWhenManifestChanges() throws Exception {
        Path root = TestCorpus.create(tmp);
        String v1 = CorpusVersion.compute(root);
        Files.writeString(root.resolve("manifest.yaml"), "# note\n", java.nio.file.StandardOpenOption.APPEND);
        assertThat(CorpusVersion.compute(root)).isNotEqualTo(v1);
    }

    @Test
    void byteLayoutMatchesSpec() throws Exception {
        Path root = TestCorpus.create(tmp);
        var bytes = new java.io.ByteArrayOutputStream();
        bytes.write(Files.readAllBytes(root.resolve("manifest.yaml")));
        for (String rel : new String[] {"corpus/adversarial/adv-x.md", "corpus/internal/eb-a.md"}) {
            String sha = Hashes.sha256Hex(Files.readAllBytes(tmp.resolve(rel)));
            bytes.write((rel + "\n" + sha + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        assertThat(CorpusVersion.compute(root)).isEqualTo(Hashes.sha256Hex(bytes.toByteArray()).substring(0, 12));
    }
}
