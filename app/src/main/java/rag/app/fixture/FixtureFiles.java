package rag.app.fixture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import rag.core.ids.Hashes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Vector-only CI fixtures (spec 05 §7.1, ROADMAP §8). Each JSONL line holds hashes and a vector, never text.
 * File and directory names carry the corpus/chunker/model keys so a stale fixture cannot be used silently.
 */
public final class FixtureFiles {

    public record FixtureChunk(String chunkId, String contentHash, String textHash, float[] embedding) {}

    public record FixtureQuestion(String itemId, String textHash, float[] embedding) {}

    public record FixtureManifest(String corpusVersion, String embeddingModel, String chunkerVersion, int chunks,
            int questions, Map<String, String> files) {}

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonMapper PRETTY = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private FixtureFiles() {}

    public static String textHash(String prefixedText) {
        return Hashes.sha256Hex(prefixedText.getBytes(StandardCharsets.UTF_8));
    }

    public static String dirName(String corpusVersion, String modelId, String chunkerVersion) {
        return corpusVersion + "-" + modelId.replace('/', '_') + "-" + chunkerVersion;
    }

    /** Writes the three files and returns the fixture directory. */
    public static Path write(Path base, FixtureManifest manifest, List<FixtureChunk> chunks,
            List<FixtureQuestion> questions) {
        Path dir = base.resolve(dirName(manifest.corpusVersion(), manifest.embeddingModel(), manifest.chunkerVersion()));
        try {
            Files.createDirectories(dir);
            writeJsonl(dir.resolve("chunks.jsonl"), chunks);
            writeJsonl(dir.resolve("questions.jsonl"), questions);
            Map<String, String> hashes = new LinkedHashMap<>();
            for (String name : List.of("chunks.jsonl", "questions.jsonl")) {
                hashes.put(name, Hashes.sha256Hex(Files.readAllBytes(dir.resolve(name))));
            }
            var written = new FixtureManifest(manifest.corpusVersion(), manifest.embeddingModel(),
                    manifest.chunkerVersion(), chunks.size(), questions.size(), hashes);
            Files.writeString(dir.resolve("manifest.json"), PRETTY.writeValueAsString(written));
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The fixture directory for these keys, or a loud failure naming what was expected. */
    public static Path locate(Path base, String corpusVersion, String modelId, String chunkerVersion) {
        String name = dirName(corpusVersion, modelId, chunkerVersion);
        Path dir = base.resolve(name);
        if (!Files.isRegularFile(dir.resolve("manifest.json"))) {
            throw new IllegalStateException("fixture not found: " + dir.resolve("manifest.json")
                    + " (expected " + name + "; run scripts/build-fixture.sh)");
        }
        return dir;
    }

    /** Vectors keyed by {@code textHash} from one JSONL file. */
    public static Map<String, float[]> readVectors(Path dir, String file) {
        Path path = dir.resolve(file);
        Map<String, float[]> out = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(path)) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = JSON.readTree(line);
                JsonNode embedding = node.get("embedding");
                float[] vector = new float[embedding.size()];
                for (int i = 0; i < vector.length; i++) {
                    vector[i] = (float) embedding.get(i).asDouble();
                }
                out.put(node.get("textHash").asString(), vector);
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeJsonl(Path file, List<?> records) throws IOException {
        StringBuilder out = new StringBuilder();
        for (Object record : records) {
            out.append(JSON.writeValueAsString(record)).append('\n');
        }
        Files.writeString(file, out.toString());
    }
}
