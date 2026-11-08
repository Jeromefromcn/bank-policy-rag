package rag.core.corpus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import rag.core.ids.Hashes;
import rag.core.parse.TestPdfs;

/** A self-made corpus directory: one generated PDF module and two Markdown documents. */
final class TestCorpus {

    private TestCorpus() {}

    static Path create(Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("corpus"));
        Files.createDirectories(root.resolve("raw"));
        Files.createDirectories(root.resolve("internal"));
        Files.createDirectories(root.resolve("adversarial"));
        Path pdf = TestPdfs.sample(root.resolve("raw"));
        writeManifest(root, Hashes.sha256Hex(Files.readAllBytes(pdf)));
        Files.writeString(root.resolve("internal/eb-a.md"), markdown("eb-a", "internal", "internal"));
        Files.writeString(root.resolve("adversarial/adv-x.md"), markdown("adv-x", "public", "adversarial"));
        return root;
    }

    static void writeManifest(Path root, String sha) throws IOException {
        Files.writeString(root.resolve("manifest.yaml"), """
                modules:
                  - docId: test-ab-1
                    code: AB-1
                    title: Sample Module
                    version: "V.1"
                    revisionDate: 2020-01-01
                    file: AB-1.pdf
                    sha256: "%s"
                """.formatted(sha));
    }

    static String markdown(String docId, String classification, String sourceType) {
        return """
                ---
                docId: %s
                title: Title %s
                version: "1.0"
                classification: %s
                sourceType: %s
                fictional: true
                ---
                ## 1 Scope
                Self-made text for %s.
                """.formatted(docId, docId, classification, sourceType, docId);
    }
}
