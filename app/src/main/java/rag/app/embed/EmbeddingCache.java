package rag.app.embed;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Embedding vectors on disk, keyed by {@code (embeddingModel, contentHash)} (ROADMAP §7.1).
 * Layout: {@code <dir>/<model with '/' → '_'>/<contentHash>.f32}, little-endian float32.
 */
public class EmbeddingCache {

    private final Path dir;

    public EmbeddingCache(Path dir) {
        this.dir = dir;
    }

    public Optional<float[]> get(String modelId, String contentHash) {
        Path f = file(modelId, contentHash);
        if (!Files.isRegularFile(f)) {
            return Optional.empty();
        }
        try {
            ByteBuffer buf = ByteBuffer.wrap(Files.readAllBytes(f)).order(ByteOrder.LITTLE_ENDIAN);
            float[] v = new float[buf.remaining() / Float.BYTES];
            buf.asFloatBuffer().get(v);
            return Optional.of(v);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void put(String modelId, String contentHash, float[] vector) {
        Path f = file(modelId, contentHash);
        ByteBuffer buf = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buf.asFloatBuffer().put(vector);
        try {
            Files.createDirectories(f.getParent());
            Path tmp = Files.createTempFile(f.getParent(), contentHash, ".tmp");
            Files.write(tmp, buf.array());
            Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path file(String modelId, String contentHash) {
        if (!contentHash.matches("[0-9a-zA-Z]+")) {
            throw new IllegalArgumentException("bad content hash: " + contentHash);
        }
        return dir.resolve(modelId.replace('/', '_')).resolve(contentHash + ".f32");
    }
}
