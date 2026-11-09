package rag.app.support;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import rag.app.rerank.RerankUnavailableException;
import rag.app.rerank.Reranker;

/** Scriptable reranker: records every call; scores come from a settable function (default: all 0, keeps order). */
public class StubReranker implements Reranker {

    public record Call(String query, List<String> passages) {}

    private final List<Call> calls = new ArrayList<>();
    private BiFunction<String, String, Double> scorer = (q, p) -> 0.0;
    private boolean failNext;

    @Override
    public synchronized List<Double> score(String query, List<String> passages) {
        calls.add(new Call(query, List.copyOf(passages)));
        if (failNext) {
            failNext = false;
            throw new RerankUnavailableException("stub reranker down");
        }
        return passages.stream().map(p -> scorer.apply(query, p)).toList();
    }

    public synchronized void scoreWith(BiFunction<String, String, Double> f) {
        scorer = f;
    }

    public synchronized void failNext() {
        failNext = true;
    }

    public synchronized List<Call> calls() {
        return List.copyOf(calls);
    }

    public synchronized void reset() {
        calls.clear();
        scorer = (q, p) -> 0.0;
        failNext = false;
    }
}
