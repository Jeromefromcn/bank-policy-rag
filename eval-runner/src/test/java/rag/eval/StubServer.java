package rag.eval;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** A local stand-in for the app: canned responses, records every request. */
final class StubServer implements AutoCloseable {

    record Request(String path, String apiKey, String body) {}

    final List<Request> requests = new ArrayList<>();
    private final HttpServer server;
    volatile int searchStatus = 200;
    volatile String searchBody = """
            {"queryId":"q_1","hits":[
              {"chunkId":"c1","docId":"eb-a","sectionPath":["1"],"coveredSections":["1"],"text":"t"},
              {"chunkId":"c2","docId":"eb-b","sectionPath":["2"],"coveredSections":["2"],"text":"t"}],
             "diagnostics":{"bestVectorSimilarity":0.8}}""";
    volatile int askStatus = 200;
    volatile String askBody = """
            {"answerId":"a_1","status":"ANSWERED","refusalReason":null,"answer":"An answer [1].",
             "citations":[{"ref":1,"chunkId":"c1","docId":"eb-a","sectionPath":["1"],"pages":[],"quote":"q"}],
             "usage":{"promptTokens":100,"completionTokens":10},"latencyMs":{"retrieval":5,"rerank":0,"generation":42}}""";
    /** When set, builds the {@code /v1/ask} response from the request body (multi-turn conversations). */
    volatile java.util.function.Function<String, String> askResponder;
    /** When set, builds the {@code /v1/search} response from the request body (to vary similarity per question). */
    volatile java.util.function.Function<String, String> searchResponder;
    volatile int judgeStatus = 200;
    /** Builds the {@code /chat/completions} response body (the whole OpenAI-style JSON) from the request body. */
    volatile java.util.function.Function<String, String> judgeResponder = body -> judgeContent("{\"verdicts\":[\"yes\"]}");
    volatile String infoBody = """
            {"rag":{"corpusVersion":"abc123","chunkerVersion":"fixed-512-64-cl100k","embeddingModel":"ollama/nomic-embed-text","index":"x","generationModel":"gen-model","answerPrompt":"answer.v2"}}""";

    StubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/search", ex -> respond(ex, searchStatus, null));
        server.createContext("/v1/ask", ex -> respond(ex, askStatus, askResponder == null ? askBody : null));
        server.createContext("/chat/completions", ex -> respond(ex, judgeStatus, null));
        server.createContext("/v1/chat/completions", ex -> respond(ex, judgeStatus, null));
        server.createContext("/actuator/info", ex -> respond(ex, 200, infoBody));
        server.start();
    }

    static String judgeContent(String content) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":"
                + tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(content) + "}}]}";
    }

    synchronized long judgeCalls() {
        return requests.stream().filter(r -> r.path().endsWith("/chat/completions")).count();
    }

    URI base() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private synchronized void respond(HttpExchange ex, int status, String body) throws IOException {
        requests.add(new Request(ex.getRequestURI().getPath(), ex.getRequestHeaders().getFirst("X-Api-Key"),
                new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        String requestBody = requests.getLast().body();
        if (body == null) {
            body = requests.getLast().path().endsWith("/chat/completions") ? judgeResponder.apply(requestBody)
                    : requests.getLast().path().equals("/v1/ask") ? askResponder.apply(requestBody)
                    : searchResponder != null ? searchResponder.apply(requestBody) : searchBody;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", status == 200 ? "application/json" : "application/problem+json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    synchronized long searches() {
        return requests.stream().filter(r -> r.path().equals("/v1/search")).count();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
