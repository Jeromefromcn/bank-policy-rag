package rag.core.corpus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import rag.core.ids.Hashes;
import rag.core.model.ParsedDocument;
import rag.core.parse.MarkdownParser;
import rag.core.parse.PdfParser;

/** Loads every corpus document: manifest PDFs (sha256-verified) first, then Markdown sorted by path. */
public final class CorpusLoader {

    private final Path root;

    public CorpusLoader(Path corpusRoot) {
        this.root = corpusRoot;
    }

    public List<ParsedDocument> load() {
        List<ParsedDocument> docs = new ArrayList<>();
        for (Manifest.Module module : Manifest.load(root.resolve("manifest.yaml")).modules()) {
            Path pdf = root.resolve("raw").resolve(module.file());
            verify(pdf, module);
            try {
                docs.add(PdfParser.parse(pdf, module.docId(), module.title(), module.version()));
            } catch (IllegalStateException e) {
                throw new IllegalStateException(module.file() + ": " + e.getMessage(), e);
            }
        }
        for (Path md : CorpusFiles.markdown(root)) {
            docs.add(MarkdownParser.parse(read(md), CorpusFiles.relative(root, md)));
        }
        Set<String> seen = new HashSet<>();
        for (ParsedDocument d : docs) {
            if (!seen.add(d.docId())) {
                throw new IllegalStateException("duplicate docId: " + d.docId());
            }
        }
        return docs;
    }

    /** docIds from the manifest and Markdown front matter, without reading any PDF. */
    public static Set<String> knownDocIds(Path corpusRoot) {
        Set<String> ids = new LinkedHashSet<>();
        Manifest.load(corpusRoot.resolve("manifest.yaml")).modules().forEach(m -> ids.add(m.docId()));
        for (Path md : CorpusFiles.markdown(corpusRoot)) {
            ids.add(MarkdownParser.parse(read(md), md.toString()).docId());
        }
        return ids;
    }

    private static void verify(Path pdf, Manifest.Module module) {
        if (!Files.isRegularFile(pdf)) {
            throw new IllegalStateException("missing " + module.file() + " under " + pdf.getParent()
                    + " (see README: Getting the SPM files)");
        }
        String actual;
        try {
            actual = Hashes.sha256Hex(Files.readAllBytes(pdf));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (!actual.equalsIgnoreCase(module.sha256())) {
            throw new IllegalStateException(module.file() + ": sha256 " + actual + " does not match manifest");
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
