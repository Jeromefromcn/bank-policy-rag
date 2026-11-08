package rag.eval;

import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import rag.core.corpus.CorpusLoader;
import rag.core.eval.GoldenItem;
import rag.core.eval.GoldenSet;
import rag.core.eval.RetrievalMetrics;

/**
 * Eval runner v1: retrieval metrics only (spec 01 §5).
 *
 * <pre>--base-url=URL [--split=all|dev|test] [--golden=eval/golden-set.yaml] [--corpus=corpus] [--out=eval/runs]</pre>
 *
 * The API key comes from {@code RAG_KEY_DEV}. Exit codes: 0 done, 1 run failed, 2 usage error.
 */
public final class EvalRunner {

    static final int TOP_K = 10;
    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private EvalRunner() {}

    public static void main(String[] args) {
        System.exit(run(args, System.getenv(), System.out));
    }

    static int run(String[] args, Map<String, String> env, PrintStream out) {
        Map<String, String> opts = new HashMap<>(Map.of("split", "all", "golden", "eval/golden-set.yaml", "corpus", "corpus",
                "out", "eval/runs"));
        for (String a : args) {
            int eq = a.indexOf('=');
            if (!a.startsWith("--") || eq < 0) {
                out.println("usage: --base-url=URL [--split=all|dev|test] [--golden=…] [--corpus=…] [--out=…]");
                return 2;
            }
            opts.put(a.substring(2, eq), a.substring(eq + 1));
        }
        String key = env.get("RAG_KEY_DEV");
        if (!opts.containsKey("base-url") || key == null || key.isBlank()) {
            out.println("error: --base-url and the RAG_KEY_DEV environment variable are required");
            return 2;
        }
        String split = opts.get("split");
        if (!List.of("all", "dev", "test").contains(split)) {
            out.println("error: --split must be all, dev or test");
            return 2;
        }

        try {
            List<GoldenItem> items = GoldenSet.load(Path.of(opts.get("golden")), CorpusLoader.knownDocIds(Path.of(opts.get("corpus"))));
            var client = new SearchClient(URI.create(opts.get("base-url")), key);
            Map<String, String> info = client.info();

            List<ItemResult> results = new ArrayList<>();
            int skipped = 0;
            for (GoldenItem item : items) {
                if (!split.equals("all") && !item.split().equals(split)) {
                    continue;
                }
                if (item.category().equals("unanswerable")) {
                    skipped++;
                    continue;
                }
                var res = client.search(item.question(), TOP_K);
                results.add(new ItemResult(item, RetrievalMetrics.score(item.expectedSources(), res.hits()), res.latencyMs()));
            }

            RunMetadata meta = RunMetadata.collect(opts.get("base-url"), info, split, skipped);
            Path dir = Path.of(opts.get("out")).resolve(RUN_ID.format(Instant.now()));
            new ReportWriter().write(dir, meta, results);
            out.println("wrote " + dir.resolve("report.md") + " (" + results.size() + " items, " + skipped + " unanswerable skipped)");
            return 0;
        } catch (RuntimeException e) {
            out.println("error: " + e.getMessage());
            return 1;
        }
    }
}
