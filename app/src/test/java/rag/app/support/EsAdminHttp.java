package rag.app.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Raw REST reads against the test container as {@code elastic}, for assertions only. */
public final class EsAdminHttp {

    private EsAdminHttp() {}

    public static String get(String path) {
        var es = EsTestContainer.get();
        String auth = Base64.getEncoder().encodeToString(("elastic:" + EsTestContainer.PASSWORD).getBytes(StandardCharsets.UTF_8));
        var req = HttpRequest.newBuilder(URI.create("http://" + es.getHttpHostAddress() + path))
                .header("Authorization", "Basic " + auth).GET().build();
        try {
            return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString()).body();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
