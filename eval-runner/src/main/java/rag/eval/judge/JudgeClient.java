package rag.eval.judge;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import rag.eval.ApiHttp;
import tools.jackson.databind.JsonNode;

/**
 * LLM judge over an OpenAI-compatible {@code /chat/completions} endpoint (spec 03 §7.2): one call per item returning a
 * binary verdict per point or per cited sentence. Any failure becomes {@code ERROR} verdicts; errors are not cached.
 */
public final class JudgeClient {

    public static final String POINT_PROMPT = "judge-point.v1";
    public static final String CITATION_PROMPT = "judge-citation.v1";

    public record SentenceWithSources(String sentence, List<String> sources) {}

    private static final int ATTEMPTS = 2;

    private final ApiHttp http;
    private final String model;
    private final JudgeCache cache;

    public JudgeClient(URI base, String apiKey, String model, JudgeCache cache) {
        this.http = new ApiHttp(base, "Authorization", "Bearer " + apiKey);
        this.model = model;
        this.cache = cache;
    }

    public String model() {
        return model;
    }

    /** @param sources the answer's sources in citation order, so {@code [n]} in the answer is element n-1 */
    public List<JudgeVerdict> judgePoints(String answer, List<String> sources, List<String> points) {
        if (points.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> numbered = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            numbered.add(Map.of("ref", i + 1, "text", sources.get(i)));
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("answer", answer);
        input.put("sources", numbered);
        input.put("points", points);
        return judge(POINT_PROMPT, input, points.size());
    }

    public List<JudgeVerdict> judgeSentences(List<SentenceWithSources> sentences) {
        if (sentences.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (SentenceWithSources s : sentences) {
            items.add(Map.of("sentence", s.sentence(), "sources", s.sources()));
        }
        return judge(CITATION_PROMPT, Map.of("items", items), sentences.size());
    }

    private List<JudgeVerdict> judge(String promptVersion, Map<String, Object> input, int expected) {
        String inputJson = ApiHttp.JSON.writeValueAsString(input);
        String key = JudgeCache.key(promptVersion, model, inputJson);
        Optional<List<JudgeVerdict>> cached = cache.get(key).flatMap(c -> parse(c, expected));
        if (cached.isPresent()) {
            return cached.get();
        }
        String prompt = template(promptVersion) + "\n\nINPUT:\n" + inputJson;
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("temperature", 0);
        request.put("response_format", Map.of("type", "json_object"));
        request.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try {
                JsonNode response = http.post("/chat/completions", request);
                String content = response.path("choices").path(0).path("message").path("content").asString("");
                Optional<List<JudgeVerdict>> verdicts = parse(content, expected);
                if (verdicts.isPresent()) {
                    cache.put(key, content);
                    return verdicts.get();
                }
            } catch (RuntimeException e) {
                // HTTP or transport failure: retry, then record ERROR
            }
        }
        return Collections.nCopies(expected, JudgeVerdict.ERROR);
    }

    private static Optional<List<JudgeVerdict>> parse(String content, int expected) {
        try {
            JsonNode verdicts = ApiHttp.JSON.readTree(content).path("verdicts");
            if (!verdicts.isArray() || verdicts.size() != expected) {
                return Optional.empty();
            }
            List<JudgeVerdict> out = new ArrayList<>();
            for (JsonNode v : verdicts) {
                switch (v.asString("").strip().toLowerCase(java.util.Locale.ROOT)) {
                    case "yes" -> out.add(JudgeVerdict.SUPPORTED);
                    case "no" -> out.add(JudgeVerdict.UNSUPPORTED);
                    default -> {
                        return Optional.empty();
                    }
                }
            }
            return Optional.of(out);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String template(String version) {
        try (InputStream in = JudgeClient.class.getClassLoader().getResourceAsStream("prompts/" + version + ".st")) {
            if (in == null) {
                throw new IllegalStateException("missing judge prompt " + version);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
