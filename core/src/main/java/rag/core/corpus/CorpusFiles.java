package rag.core.corpus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Locates the Markdown documents of a corpus directory, in a stable order. */
final class CorpusFiles {

    static final List<String> MARKDOWN_DIRS = List.of("adversarial", "internal");

    private CorpusFiles() {}

    /** Markdown files sorted by their path relative to the corpus directory's parent. */
    static List<Path> markdown(Path corpusRoot) {
        List<Path> files = new ArrayList<>();
        for (String dir : MARKDOWN_DIRS) {
            Path d = corpusRoot.resolve(dir);
            if (!Files.isDirectory(d)) {
                continue;
            }
            try (Stream<Path> s = Files.list(d)) {
                s.filter(p -> p.getFileName().toString().endsWith(".md")).forEach(files::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        files.sort(Comparator.comparing(p -> relative(corpusRoot, p)));
        return files;
    }

    /** e.g. {@code corpus/internal/eb-x.md}, using the corpus directory's own name. */
    static String relative(Path corpusRoot, Path file) {
        Path abs = corpusRoot.toAbsolutePath().normalize();
        return abs.getFileName() + "/" + abs.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }
}
