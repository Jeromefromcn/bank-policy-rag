package rag.core.corpus;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import rag.core.ids.Hashes;

/**
 * First 12 hex of sha256 over the manifest bytes followed by, for each Markdown document sorted by
 * path, {@code relPath + "\n" + sha256hex(file) + "\n"} (spec 01 §3.5).
 */
public final class CorpusVersion {

    private CorpusVersion() {}

    public static String compute(Path corpusRoot) {
        try {
            var bytes = new ByteArrayOutputStream();
            bytes.write(Files.readAllBytes(corpusRoot.resolve("manifest.yaml")));
            for (Path md : CorpusFiles.markdown(corpusRoot)) {
                String line = CorpusFiles.relative(corpusRoot, md) + "\n" + Hashes.sha256Hex(Files.readAllBytes(md)) + "\n";
                bytes.write(line.getBytes(StandardCharsets.UTF_8));
            }
            return Hashes.sha256Hex(bytes.toByteArray()).substring(0, 12);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
