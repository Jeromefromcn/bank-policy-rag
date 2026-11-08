package rag.eval;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/** What a report must record so its numbers can be reproduced (spec 00 §6.3). */
public record RunMetadata(
        String timestamp,
        String gitCommit,
        boolean gitDirty,
        String arch,
        int cpus,
        String baseUrl,
        Map<String, String> info,
        String split,
        int skipped) {

    public static RunMetadata collect(String baseUrl, Map<String, String> info, String split, int skipped) {
        return new RunMetadata(Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(), git("rev-parse", "HEAD"),
                !git("status", "--porcelain").isEmpty(), System.getProperty("os.arch"),
                Runtime.getRuntime().availableProcessors(), baseUrl, java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(info)), split, skipped);
    }

    private static String git(String... args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).strip();
            return p.waitFor() == 0 ? out : "unknown";
        } catch (IOException e) {
            return "unknown";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "unknown";
        }
    }
}
