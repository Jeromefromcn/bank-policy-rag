package rag.app.rerank;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import rag.app.RagProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Client for the reranker sidecar's {@code POST /v1/rerank} (spec 04 §3). Results are mapped back by {@code index};
 * a missing, duplicate or out-of-range index fails the call rather than returning a shorter list.
 */
@Component
public class HttpReranker implements Reranker {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient client;

    @Autowired
    public HttpReranker(RagProperties props) {
        this(props.rerank());
    }

    HttpReranker(RagProperties.Rerank cfg) {
        var http = HttpClient.newBuilder().connectTimeout(cfg.timeout()).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(cfg.timeout());
        this.client = RestClient.builder().baseUrl(cfg.url()).requestFactory(factory).build();
    }

    @Override
    public List<Double> score(String query, List<String> passages) {
        if (passages.isEmpty()) {
            return List.of();
        }
        String body;
        try {
            body = client.post().uri("/v1/rerank").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("query", query, "documents", passages, "top_n", passages.size()))
                    .retrieve().body(String.class);
        } catch (RuntimeException e) {
            throw new RerankUnavailableException("reranker call failed: " + e.getMessage(), e);
        }
        return parse(body, passages.size());
    }

    private static List<Double> parse(String body, int n) {
        JsonNode results;
        try {
            results = JSON.readTree(body == null ? "" : body).get("results");
        } catch (RuntimeException e) {
            throw new RerankUnavailableException("reranker returned malformed JSON", e);
        }
        if (results == null || !results.isArray()) {
            throw new RerankUnavailableException("reranker response has no results array");
        }
        Double[] scores = new Double[n];
        for (JsonNode r : results) {
            JsonNode index = r.get("index");
            JsonNode score = r.get("relevance_score");
            if (index == null || !index.isIntegralNumber() || score == null || !score.isNumber()) {
                throw new RerankUnavailableException("reranker result without a numeric index and score");
            }
            int i = index.asInt();
            if (i < 0 || i >= n || scores[i] != null) {
                throw new RerankUnavailableException("reranker returned an unknown or duplicate index " + i);
            }
            scores[i] = score.asDouble();
        }
        if (Arrays.stream(scores).anyMatch(s -> s == null)) {
            throw new RerankUnavailableException("reranker returned fewer scores than passages");
        }
        return new ArrayList<>(Arrays.asList(scores));
    }
}
