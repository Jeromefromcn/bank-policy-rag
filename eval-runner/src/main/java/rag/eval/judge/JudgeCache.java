package rag.eval.judge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * On-disk cache of judge verdicts, keyed by {@code sha256(promptVersion + model + input)}. The directory holds source
 * text, so it is gitignored. A changed prompt version or model misses.
 */
public final class JudgeCache {

    private final Path dir;

    public JudgeCache(Path dir) {
        this.dir = dir;
    }

    public static String key(String promptVersion, String model, String input) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            // Length-prefix each part so ("ab","c") and ("a","bc") cannot collide.
            for (String part : new String[] {promptVersion, model, input}) {
                sha.update((part.length() + ":").getBytes(StandardCharsets.UTF_8));
                sha.update(part.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<String> get(String key) {
        Path file = dir.resolve(key + ".json");
        try {
            return Files.exists(file) ? Optional.of(Files.readString(file)) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void put(String key, String value) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(key + ".json"), value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
