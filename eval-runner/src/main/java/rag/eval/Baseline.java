package rag.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Example Bank CI baseline (spec 05 §7.2). Numbers come only from a real gate run; updating the file is
 * its own commit with an explanation.
 */
public record Baseline(String scope, String corpusVersion, String chunkerVersion, String embeddingModel, String mode,
        int topK, boolean rerank, int includedItems, int scoredItems, int unanswerableItems, int excludedItems,
        Overall overall, Map<String, Overall> byCategory, String generatedAt) {

    public record Overall(double recallAt5, double mrr, double ndcgAt10, int n) {}

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private static final JsonMapper PRETTY = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    public Baseline {
        byCategory = byCategory == null ? Map.of() : Map.copyOf(byCategory);
    }

    public static Baseline read(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("baseline not found: " + file
                    + " (run ./gradlew :app:ciGate -PciWriteBaseline=true)");
        }
        try {
            return JSON.readValue(Files.readString(file), Baseline.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void write(Path file, Baseline baseline) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, PRETTY.writeValueAsString(baseline));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
