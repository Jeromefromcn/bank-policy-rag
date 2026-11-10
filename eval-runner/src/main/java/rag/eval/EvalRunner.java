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
import rag.eval.judge.JudgeCache;
import rag.eval.judge.JudgeClient;

/**
 * Eval runner: retrieval metrics only (spec 01 §5); {@code --mode} selects the search mode (spec 02 §7).
 *
 * <pre>--base-url=URL [--split=all|dev|test] [--golden=eval/golden-set.yaml] [--corpus=corpus] [--out=eval/runs]</pre>
 *
 * The API key comes from {@code RAG_KEY_DEV}. Exit codes: 0 done, 1 run failed, 2 usage error.
 */
public final class EvalRunner {

    static final int TOP_K = 10;
    /** Sources given to the model by /v1/ask during generation evals (the API default). */
    static final int ASK_TOP_K = 5;
    static final String DEFAULT_JUDGE_MODEL = "deepseek-v4-pro";
    static final List<String> REQUIRED_INFO = List.of("corpusVersion", "chunkerVersion", "embeddingModel");
    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private EvalRunner() {}

    public static void main(String[] args) {
        System.exit(run(args, System.getenv(), System.out));
    }

    static int run(String[] args, Map<String, String> env, PrintStream out) {
        Map<String, String> opts = new HashMap<>(Map.of("split", "all", "golden", "eval/golden-set.yaml", "corpus", "corpus",
                "out", "eval/runs", "mode", "HYBRID", "judge-model", DEFAULT_JUDGE_MODEL, "judge-url", "https://api.deepseek.com",
                "judge-cache", "eval/.judge-cache", "min-overlap", "0.5"));
        opts.put("rerank", "false");
        opts.put("repeat", "1");
        for (String a : args) {
            int eq = a.indexOf('=');
            if (!a.startsWith("--") || eq < 0) {
                out.println("usage: --base-url=URL [--split=all|dev|test] [--mode=VECTOR|BM25|HYBRID] [--rerank=true [--reranker-model=ID]] [--repeat=N] [--sweep=true] [--overlap-sweep=RUN_DIR] [--export-labels=RUN_DIR [--seed=N] [--n=20]] [--agreement=RUN_DIR [--labels=FILE]] [--multi-turn=true [--multi-turn-file=eval/multi-turn.yaml]] [--generation=true --judge-model=… --judge-url=… --judge-cache=… --min-overlap=…] [--golden=…] [--corpus=…] [--out=…]");
                return 2;
            }
            opts.put(a.substring(2, eq), a.substring(eq + 1));
        }
        if (opts.containsKey("overlap-sweep")) {
            try {
                OverlapSweep.run(Path.of(opts.get("overlap-sweep")));
                out.println("wrote " + Path.of(opts.get("overlap-sweep")).resolve("overlap-sweep.md"));
                return 0;
            } catch (RuntimeException e) {
                out.println("error: " + e.getMessage());
                return 1;
            }
        }
        if (opts.containsKey("export-labels") || opts.containsKey("agreement")) {
            return labelTools(opts, out);
        }
        boolean generation = opts.getOrDefault("generation", "false").equals("true");
        String judgeKey = env.get("DEEPSEEK_API_KEY");
        if (generation && (judgeKey == null || judgeKey.isBlank())) {
            out.println("error: --generation needs the DEEPSEEK_API_KEY environment variable");
            return 2;
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
        String mode = opts.get("mode");
        if (!List.of("VECTOR", "BM25", "HYBRID").contains(mode)) {
            out.println("error: --mode must be VECTOR, BM25 or HYBRID");
            return 2;
        }
        if (!List.of("true", "false").contains(opts.get("rerank"))) {
            out.println("error: --rerank must be true or false");
            return 2;
        }
        boolean rerank = opts.get("rerank").equals("true");
        int repeat;
        try {
            repeat = Integer.parseInt(opts.get("repeat"));
        } catch (NumberFormatException e) {
            repeat = 0;
        }
        if (repeat < 1) {
            out.println("error: --repeat must be a positive integer");
            return 2;
        }

        try {
            List<GoldenItem> items = GoldenSet.load(Path.of(opts.get("golden")), CorpusLoader.knownDocIds(Path.of(opts.get("corpus"))));
            var client = new SearchClient(URI.create(opts.get("base-url")), key, mode, rerank);
            Map<String, String> info = new java.util.TreeMap<>(client.info());
            for (String required : REQUIRED_INFO) {
                if (info.getOrDefault(required, "").isBlank()) {
                    // No live index behind the alias (wrong RAG_ENV_PREFIX, or a rebuild in progress):
                    // scoring it would produce an all-zero report.
                    out.println("error: /actuator/info has no " + required + "; is the chunks index ingested?");
                    return 1;
                }
            }

            if (rerank) {
                info.put("rerank", "true");
                info.put("rerankerModel", opts.getOrDefault("reranker-model", "unrecorded"));
            }
            if (repeat > 1) {
                info.put("repeat", Integer.toString(repeat));
            }
            if (opts.getOrDefault("sweep", "false").equals("true")) {
                var observations = Sweep.collect(items, KeySet.fromEnv(env), URI.create(opts.get("base-url")), mode, rerank);
                Path dir = Path.of(opts.get("out")).resolve(RUN_ID.format(Instant.now()) + "-sweep");
                Sweep.write(dir, RunMetadata.collect(opts.get("base-url"), info, "dev", mode, 0), Sweep.table(observations));
                out.println("wrote " + dir.resolve("sweep.md") + " (" + observations.size() + " observations)");
                return 0;
            }

            if (opts.getOrDefault("multi-turn", "false").equals("true")) {
                Map<String, GoldenItem> golden = new java.util.LinkedHashMap<>();
                items.forEach(i -> golden.put(i.id(), i));
                var multiTurn = MultiTurnSet.load(Path.of(opts.getOrDefault("multi-turn-file", "eval/multi-turn.yaml")), golden);
                KeySet keys = KeySet.fromEnv(env);
                keys.require(multiTurn.stream().filter(m -> split.equals("all") || m.split().equals(split))
                        .map(MultiTurnItem::minClearance).collect(java.util.stream.Collectors.toSet()));
                info.put("rewritePrompt", "rewrite.v1");
                var outcomes = MultiTurnRun.run(multiTurn, split, golden, keys,
                        new MultiTurnRun.Config(URI.create(opts.get("base-url")), mode, rerank, TOP_K));
                Path dir = Path.of(opts.get("out")).resolve(RUN_ID.format(Instant.now()) + "-multi-turn");
                MultiTurnReport.write(dir, RunMetadata.collect(opts.get("base-url"), info, split, mode, 0), outcomes);
                out.println("wrote " + dir.resolve("report.md") + " (" + outcomes.size() + " items)");
                return 0;
            }

            if (generation) {
                var judge = new JudgeClient(URI.create(opts.get("judge-url")), judgeKey, opts.get("judge-model"),
                        new JudgeCache(Path.of(opts.get("judge-cache"))));
                var cfg = new GenerationRun.Config(URI.create(opts.get("base-url")), mode, ASK_TOP_K, Double.parseDouble(opts.get("min-overlap")));
                var outcomes = GenerationRun.run(items, split, KeySet.fromEnv(env), judge, cfg);
                Map<String, String> reportInfo = new java.util.TreeMap<>(info);
                reportInfo.put("judgeModel", judge.model());
                reportInfo.put("judgePrompts", JudgeClient.POINT_PROMPT + "," + JudgeClient.CITATION_PROMPT);
                reportInfo.put("minOverlap", opts.get("min-overlap"));
                Path dir = Path.of(opts.get("out")).resolve(RUN_ID.format(Instant.now()) + "-generation");
                GenerationReport.write(dir, RunMetadata.collect(opts.get("base-url"), reportInfo, split, mode, 0), outcomes,
                        GenerationMetrics.compute(outcomes));
                out.println("wrote " + dir.resolve("report.md") + " (" + outcomes.size() + " calls)");
                return 0;
            }

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
                var first = client.search(item.question(), TOP_K);
                List<ItemResult.Latency> latencies = new ArrayList<>();
                latencies.add(latency(first));
                for (int r = 1; r < repeat; r++) {
                    latencies.add(latency(client.search(item.question(), TOP_K)));
                }
                results.add(new ItemResult(item, RetrievalMetrics.score(item.expectedSources(), first.hits()), latencies));
            }

            RunMetadata meta = RunMetadata.collect(opts.get("base-url"), info, split, mode, skipped);
            Path dir = Path.of(opts.get("out")).resolve(RUN_ID.format(Instant.now()));
            new ReportWriter().write(dir, meta, results);
            out.println("wrote " + dir.resolve("report.md") + " (" + results.size() + " items, " + skipped + " unanswerable skipped)");
            return 0;
        } catch (RuntimeException e) {
            out.println("error: " + e.getMessage());
            return 1;
        }
    }

    private static ItemResult.Latency latency(SearchClient.SearchResult r) {
        return new ItemResult.Latency(r.latencyMs(), r.serverRetrievalMs(), r.serverRerankMs());
    }

    /** {@code --export-labels=RUN_DIR} writes the blind sheet; {@code --agreement=RUN_DIR} scores the author's labels. */
    private static int labelTools(Map<String, String> opts, PrintStream out) {
        try {
            if (opts.containsKey("export-labels")) {
                Path sheet = Path.of(opts.getOrDefault("label-sheet", "eval/.human-labels-sheet.md"));
                var sample = LabelSheet.export(Path.of(opts.get("export-labels")), Long.parseLong(opts.getOrDefault("seed", "1")),
                        Integer.parseInt(opts.getOrDefault("n", "20")), sheet);
                out.println("wrote " + sheet + " (" + sample.size() + " points; label them in eval/human-labels.yaml)");
                return 0;
            }
            var points = Agreement.load(Path.of(opts.getOrDefault("labels", "eval/human-labels.yaml")), Path.of(opts.get("agreement")),
                    Integer.parseInt(opts.getOrDefault("n", "20")));
            var r = Agreement.compute(points);
            out.printf("judge-human agreement: %d/%d = %.3f, Cohen's kappa = %s%n", r.agree(), r.n(), r.agreement(),
                    r.kappa() == null ? "undefined" : String.format("%.3f", r.kappa()));
            return 0;
        } catch (RuntimeException e) {
            out.println("error: " + e.getMessage());
            return 1;
        }
    }
}
