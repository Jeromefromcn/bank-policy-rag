package rag.eval;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import rag.core.cite.CitationReport;
import rag.core.cite.CitationValidator;
import rag.core.eval.GoldenItem;
import rag.core.model.Clearance;
import rag.eval.AskClient.AskResult;
import rag.eval.Sweep.Kind;
import rag.eval.judge.JudgeClient;
import rag.eval.judge.JudgeVerdict;

/**
 * Runs the golden set through {@code /v1/ask} (spec 03 §7.1) and judges answered, answerable calls. Cited-source text
 * is recovered by re-running the same search with the same key; a chunk that cannot be recovered gives {@code ERROR}.
 */
public final class GenerationRun {

    public record Config(URI base, String mode, int topK, double minOverlap) {}

    public record SentenceOutcome(String text, List<Integer> refs, double overlap, boolean flagged, JudgeVerdict verdict) {}

    public record ItemOutcome(GoldenItem item, Kind kind, AskResult ask, List<JudgeVerdict> pointVerdicts,
            List<SentenceOutcome> sentences) {}

    private GenerationRun() {}

    public static List<ItemOutcome> run(List<GoldenItem> items, String split, KeySet keys, JudgeClient judge, Config cfg) {
        List<ItemOutcome> out = new ArrayList<>();
        for (GoldenItem item : items) {
            if (!split.equals("all") && !item.split().equals(split)) {
                continue;
            }
            switch (item.category()) {
                case "unanswerable" -> out.add(refusalCheck(item, Kind.UNANSWERABLE, keys.forClearance(Clearance.RESTRICTED), cfg));
                case "restricted" -> {
                    out.add(answerable(item, keys.forClearance(item.minClearance()), judge, cfg));
                    out.add(refusalCheck(item, Kind.BELOW_CLEARANCE, keys.forClearance(KeySet.below(item.minClearance())), cfg));
                }
                default -> out.add(answerable(item, keys.forClearance(item.minClearance()), judge, cfg));
            }
        }
        return out;
    }

    private static ItemOutcome refusalCheck(GoldenItem item, Kind kind, String key, Config cfg) {
        return new ItemOutcome(item, kind, ask(item, key, cfg), List.of(), List.of());
    }

    private static AskResult ask(GoldenItem item, String key, Config cfg) {
        try {
            return new AskClient(cfg.base(), key).ask(item.question(), cfg.topK(), cfg.mode());
        } catch (RuntimeException e) {
            return new AskResult("ERROR", e.getMessage(), null, List.of(), null, null, 0);
        }
    }

    private static ItemOutcome answerable(GoldenItem item, String key, JudgeClient judge, Config cfg) {
        AskResult ask = ask(item, key, cfg);
        if (!ask.status().equals("ANSWERED")) {
            return new ItemOutcome(item, Kind.ANSWERABLE, ask, List.of(), List.of());
        }
        List<String> sources = recoverSources(item, key, ask, cfg);
        if (sources == null) {
            return new ItemOutcome(item, Kind.ANSWERABLE, ask, Collections.nCopies(item.expectedPoints().size(), JudgeVerdict.ERROR),
                    List.of());
        }
        List<JudgeVerdict> points = judge.judgePoints(ask.answer(), sources, item.expectedPoints());

        CitationReport report = new CitationValidator(cfg.minOverlap()).validate(ask.answer(), sources);
        List<JudgeClient.SentenceWithSources> toJudge = new ArrayList<>();
        for (CitationReport.SentenceCheck s : report.sentences()) {
            toJudge.add(new JudgeClient.SentenceWithSources(s.text(), s.refs().stream().map(r -> sources.get(r - 1)).toList()));
        }
        List<JudgeVerdict> verdicts = judge.judgeSentences(toJudge);
        List<SentenceOutcome> sentences = new ArrayList<>();
        for (int i = 0; i < verdicts.size(); i++) {
            var s = report.sentences().get(i);
            sentences.add(new SentenceOutcome(s.text(), s.refs(), s.overlap(), s.flagged(), verdicts.get(i)));
        }
        return new ItemOutcome(item, Kind.ANSWERABLE, ask, points, sentences);
    }

    /** Sources in citation order (index = ref - 1); uncited positions hold a placeholder. Null if a cited chunk is missing. */
    private static List<String> recoverSources(GoldenItem item, String key, AskResult ask, Config cfg) {
        Map<String, String> text;
        try {
            text = new SearchClient(cfg.base(), key, cfg.mode()).search(item.question(), cfg.topK()).textByChunkId();
        } catch (RuntimeException e) {
            return null;
        }
        int max = ask.citations().stream().mapToInt(AskClient.AskCitation::ref).max().orElse(0);
        List<String> sources = new ArrayList<>(Collections.nCopies(max, "(not cited)"));
        for (AskClient.AskCitation c : ask.citations()) {
            String t = text.get(c.chunkId());
            if (t == null) {
                return null;
            }
            sources.set(c.ref() - 1, t);
        }
        return sources;
    }
}
