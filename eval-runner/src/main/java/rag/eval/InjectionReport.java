package rag.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Writes {@code report.json} / {@code report.md}: ids, statuses, canary hits and booleans only, never answer text. */
public final class InjectionReport {

    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private InjectionReport() {}

    public static void write(Path dir, RunMetadata meta, List<InjectionRun.Result> results) {
        long passed = results.stream().filter(r -> r.check().pass()).count();
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("metadata", meta);
        json.put("totals", Map.of("n", results.size(), "pass", passed, "fail", results.size() - passed));
        List<Map<String, Object>> rows = results.stream().map(r -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.item().id());
            row.put("clearance", r.item().clearance().wire());
            row.put("expectedStatus", r.item().expectedStatus());
            row.put("status", r.check().status());
            row.put("refusalReason", r.check().refusalReason());
            row.put("pass", r.check().pass());
            row.put("canaryHits", r.check().canaryHits());
            row.put("citedDocIds", r.check().citedDocIds());
            row.put("error", r.error());
            return row;
        }).toList();
        json.put("items", rows);

        StringBuilder md = new StringBuilder("# Injection eval report\n\n");
        md.append("| Key | Value |\n|---|---|\n");
        md.append("| timestamp | ").append(meta.timestamp()).append(" |\n");
        md.append("| git commit | ").append(meta.gitCommit()).append(meta.gitDirty() ? " (dirty)" : "").append(" |\n");
        md.append("| items | ").append(results.size()).append(" |\n");
        md.append("| pass / fail | ").append(passed).append(" / ").append(results.size() - passed).append(" |\n");
        md.append("\n| Id | Clearance | Expected | Status | Pass | Canaries | Forbidden citations | Error |\n")
                .append("|---|---|---|---|---|---|---|---|\n");
        for (InjectionRun.Result r : results) {
            md.append("| ").append(r.item().id()).append(" | ").append(r.item().clearance().wire()).append(" | ")
                    .append(r.item().expectedStatus()).append(" | ").append(r.check().status()).append(" | ")
                    .append(r.check().pass()).append(" | ").append(r.check().canaryHits()).append(" | ")
                    .append(r.check().citedDocIds()).append(" | ").append(r.error() == null ? "" : r.error()).append(" |\n");
        }

        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("report.json"), JSON.writeValueAsString(json));
            Files.writeString(dir.resolve("report.md"), md.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
