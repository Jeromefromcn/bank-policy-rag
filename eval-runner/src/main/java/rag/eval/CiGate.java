package rag.eval;

import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import rag.core.corpus.CorpusLoader;
import rag.core.eval.EvalScope;
import rag.core.eval.GoldenItem;
import rag.core.eval.GoldenSet;
import rag.core.eval.ItemScores;
import rag.core.eval.RetrievalMetrics;
import rag.core.model.Clearance;

/**
 * The Example Bank retrieval gate (spec 05 §7.2): 24 scored items, each called with the key at its
 * {@code minClearance}, HYBRID, top 10, no rerank. Fails on an overall Recall@5 or MRR drop &gt; 0.02.
 */
public final class CiGate {

    public static final int TOP_K = 10;
    public static final double MAX_DROP = 0.02;

    public record ScopeResult(int included, int scored, int unanswerable, List<String> excludedIds) {}

    public record Result(int included, int scored, int unanswerable, List<String> excludedIds, double recall5,
            double mrr, double ndcg10, Map<String, Baseline.Overall> byCategory) {}

    private CiGate() {}

    public static ScopeResult scope(List<GoldenItem> items, Set<String> exampleBankDocIds) {
        int included = 0;
        int scored = 0;
        int unanswerable = 0;
        List<String> excluded = new ArrayList<>();
        for (GoldenItem item : items) {
            if (!EvalScope.inExampleBank(item, exampleBankDocIds)) {
                excluded.add(item.id());
                continue;
            }
            included++;
            if (item.category().equals("unanswerable")) {
                unanswerable++;
            } else {
                scored++;
            }
        }
        return new ScopeResult(included, scored, unanswerable, List.copyOf(excluded));
    }

    public static Result run(URI base, Map<Clearance, String> keys, Path golden, Path corpus, Path baseline,
            boolean writeBaseline, PrintStream out) {
        List<GoldenItem> items = GoldenSet.load(golden, CorpusLoader.knownDocIds(corpus));
        Set<String> exampleBank = CorpusLoader.knownDocIds(corpus, CorpusLoader.Scope.EXAMPLE_BANK);
        ScopeResult scope = scope(items, exampleBank);
        List<GoldenItem> included = items.stream().filter(i -> EvalScope.inExampleBank(i, exampleBank)).toList();

        List<ItemResult> results = new ArrayList<>();
        for (GoldenItem item : included) {
            if (item.category().equals("unanswerable")) {
                continue;
            }
            String key = keys.get(item.minClearance());
            if (key == null || key.isBlank()) {
                throw new IllegalStateException("missing API key for " + item.minClearance().wire() + " items");
            }
            var first = new SearchClient(base, key, "HYBRID").search(item.question(), TOP_K);
            results.add(new ItemResult(item, RetrievalMetrics.score(item.expectedSources(), first.hits()), first.latencyMs()));
        }

        List<ReportWriter.Aggregate> aggregates = ReportWriter.aggregate(results);
        ReportWriter.Aggregate all = aggregates.stream().filter(a -> a.group().equals("all")).findFirst()
                .orElseThrow(() -> new IllegalStateException("no scored items in the gate"));
        Map<String, Baseline.Overall> byCategory = new LinkedHashMap<>();
        aggregates.stream().filter(a -> !a.group().equals("all"))
                .forEach(a -> byCategory.put(a.group(), new Baseline.Overall(a.recall5(), a.mrr(), a.ndcg10(), a.n())));
        Result result = new Result(scope.included(), scope.scored(), scope.unanswerable(), scope.excludedIds(),
                all.recall5(), all.mrr(), all.ndcg10(), byCategory);

        String key = keys.values().iterator().next();
        Map<String, String> info = new SearchClient(base, key, "HYBRID").info();
        out.println("scope example-bank: " + scope.included() + " included (" + scope.scored() + " scored, "
                + scope.unanswerable() + " unanswerable), " + scope.excludedIds().size() + " excluded");
        out.printf(Locale.ROOT, "Recall@5 %.3f, MRR %.3f, nDCG@10 %.3f (n=%d)%n", result.recall5(), result.mrr(),
                result.ndcg10(), all.n());

        if (writeBaseline) {
            Baseline.write(baseline, new Baseline("example-bank", info.get("corpusVersion"), info.get("chunkerVersion"),
                    info.get("embeddingModel"), "HYBRID", TOP_K, false, result.included(), result.scored(),
                    result.unanswerable(), result.excludedIds().size(),
                    new Baseline.Overall(result.recall5(), result.mrr(), result.ndcg10(), all.n()), byCategory,
                    Instant.now().toString()));
            out.println("wrote baseline " + baseline);
        } else {
            Baseline stored = Baseline.read(baseline);
            assertProvenance(stored, info);
            assertNoRegression(result, stored);
            out.println("gate passed against " + baseline);
        }
        return result;
    }

    /** A stale baseline (different corpus, chunker, model or gate configuration) is a hard failure. */
    public static void assertProvenance(Baseline baseline, Map<String, String> info) {
        if (!"example-bank".equals(baseline.scope())) {
            throw new IllegalStateException("baseline scope " + baseline.scope() + " is not example-bank");
        }
        if (!"HYBRID".equals(baseline.mode())) {
            throw new IllegalStateException("baseline mode " + baseline.mode() + " is not HYBRID; regenerate it");
        }
        if (baseline.topK() != TOP_K || baseline.rerank()) {
            throw new IllegalStateException("baseline gate configuration (topK " + baseline.topK() + ", rerank "
                    + baseline.rerank() + ") differs from topK " + TOP_K + ", no rerank; regenerate it");
        }
        check("corpusVersion", baseline.corpusVersion(), info.get("corpusVersion"));
        check("chunkerVersion", baseline.chunkerVersion(), info.get("chunkerVersion"));
        check("embeddingModel", baseline.embeddingModel(), info.get("embeddingModel"));
    }

    private static void check(String name, String expected, String actual) {
        if (expected == null || actual == null || !expected.equals(actual)) {
            throw new IllegalStateException("baseline " + name + " " + expected + " does not match the running index ("
                    + actual + "); regenerate the baseline with -PciWriteBaseline=true");
        }
    }

    public static void assertNoRegression(Result run, Baseline baseline) {
        double recallDrop = baseline.overall().recallAt5() - run.recall5();
        if (recallDrop > MAX_DROP + 1e-9) {
            throw new IllegalStateException("Recall@5 dropped by " + format(recallDrop) + " (baseline "
                    + format(baseline.overall().recallAt5()) + ", run " + format(run.recall5()) + ")");
        }
        double mrrDrop = baseline.overall().mrr() - run.mrr();
        if (mrrDrop > MAX_DROP + 1e-9) {
            throw new IllegalStateException("MRR dropped by " + format(mrrDrop) + " (baseline "
                    + format(baseline.overall().mrr()) + ", run " + format(run.mrr()) + ")");
        }
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
