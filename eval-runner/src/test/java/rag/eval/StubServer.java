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
              {"chunkId":"c2","docId":"eb-b","sectionPath":["2"],"coveredSections":["2"],"text":"t"}]}""";
    volatile String infoBody = """
            {"rag":{"corpusVersion":"abc123","chunkerVersion":"fixed-512-64-cl100k","embeddingModel":"ollama/nomic-embed-text","index":"x"}}""";

    StubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/search", ex -> respond(ex, searchStatus, searchBody));
        server.createContext("/actuator/info", ex -> respond(ex, 200, infoBody));
        server.start();
    }

    URI base() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private synchronized void respond(HttpExchange ex, int status, String body) throws IOException {
        requests.add(new Request(ex.getRequestURI().getPath(), ex.getRequestHeaders().getFirst("X-Api-Key"),
                new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
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
