package rag.core.corpus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** {@code corpus/manifest.yaml}: the SPM modules in scope (no URLs by design). */
public record Manifest(List<Module> modules) {

    public record Module(String docId, String code, String title, String version, String revisionDate, String file,
            String sha256) {}

    public Manifest {
        modules = List.copyOf(modules);
    }

    @SuppressWarnings("unchecked")
    public static Manifest load(Path file) {
        String yaml;
        try {
            yaml = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
        Map<String, Object> root = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        List<Map<String, Object>> modules = (List<Map<String, Object>>) root.get("modules");
        if (modules == null) {
            throw new IllegalStateException(file + ": no modules");
        }
        return new Manifest(modules.stream()
                .map(m -> new Module(str(m, "docId"), str(m, "code"), str(m, "title"), str(m, "version"),
                        str(m, "revisionDate"), str(m, "file"), str(m, "sha256")))
                .toList());
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            throw new IllegalStateException("manifest module lacks " + key + ": " + m);
        }
        // YAML reads unquoted dates as timestamps; keep the ISO date as written.
        if (v instanceof Date d) {
            return d.toInstant().atZone(ZoneOffset.UTC).toLocalDate().toString();
        }
        return v.toString();
    }
}
