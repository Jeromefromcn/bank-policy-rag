package rag.app.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import rag.app.embed.Embedder;
import rag.app.embed.EmbeddingUnavailableException;
import rag.app.es.ChunkIndexAdmin;
import rag.app.es.ChunkWriter;
import rag.app.support.Http;
import rag.app.support.IntegrationTest;
import rag.app.support.TestIndex;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@TestPropertySource(properties = "rag.env-prefix=t_search")
class SearchControllerIT extends IntegrationTest {

    static final String QUERY = "tolerance for disruption";
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired ChunkIndexAdmin admin;
    @Autowired ChunkWriter writer;
    @MockitoSpyBean Embedder embedder;

    @BeforeEach
    void index() {
        if (admin.aliasInfo("t_search-chunks").isEmpty()) {
            TestIndex.rebuild(admin, writer, "t_search", TestIndex.mixed(QUERY));
        }
    }

    Http.Response post(Map<String, Object> body) {
        return Http.post(port, "/v1/search", KEY, JSON.writeValueAsString(body));
    }

    void assertProblem(Http.Response r, int status, String type) {
        assertThat(r.status()).as(r.body()).isEqualTo(status);
        assertThat(r.contentType()).startsWith("application/problem+json");
        assertThat(JSON.readTree(r.body()).get("type").asString()).isEqualTo(type);
    }

    @Test
    void searchReturnsContractShape() {
        var r = post(Map.of("query", QUERY, "topK", 3));
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        JsonNode body = JSON.readTree(r.body());
        assertThat(body.get("queryId").asString()).startsWith("q_").hasSize(28);
        JsonNode hits = body.get("hits");
        assertThat(hits.size()).isEqualTo(3);
        JsonNode first = hits.get(0);
        for (String f : List.of("chunkId", "sectionId", "docId", "docTitle", "sectionPath", "coveredSections", "pages",
                "classification", "score", "scores", "text")) {
            assertThat(first.has(f)).as(f).isTrue();
        }
        assertThat(first.get("scores").get("vectorRank").asInt()).isEqualTo(1);
        assertThat(first.get("chunkId").asString()).isEqualTo("c-int");
    }

    @Test
    void defaultsToFiveVectorHits() {
        var r = post(Map.of("query", QUERY, "mode", "VECTOR"));
        assertThat(JSON.readTree(r.body()).get("hits").size()).isEqualTo(5);
    }

    @Test
    void publicKeySeesOnlyPublic() {
        var r = Http.post(port, "/v1/search", PUBLIC_KEY, JSON.writeValueAsString(Map.of("query", QUERY)));
        JSON.readTree(r.body()).get("hits").forEach(h -> assertThat(h.get("classification").asString()).isEqualTo("public"));
    }

    @Test
    void rejectsInvalidRequests() {
        for (Map<String, Object> body : List.<Map<String, Object>>of(Map.of(), Map.of("query", " "),
                Map.of("query", "x", "topK", 0), Map.of("query", "x", "topK", 51))) {
            assertProblem(post(body), 400, "urn:rag:problem:invalid-request");
        }
    }

    @Test
    void malformedJsonIs400() {
        var r = Http.post(port, "/v1/search", KEY, "{not json");
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.contentType()).startsWith("application/problem+json");
    }

    @Test
    void hybridAndRerankUnsupported() {
        assertProblem(post(Map.of("query", "x", "mode", "HYBRID")), 400, "urn:rag:problem:unsupported-mode");
        assertProblem(post(Map.of("query", "x", "mode", "BM25")), 400, "urn:rag:problem:unsupported-mode");
        assertProblem(post(Map.of("query", "x", "rerank", true)), 400, "urn:rag:problem:unsupported-mode");
    }

    @Test
    void embeddingFailureReturns503() {
        doThrow(new EmbeddingUnavailableException("down", null)).when(embedder).embedQuery(anyString());
        assertProblem(post(Map.of("query", "x")), 503, "urn:rag:problem:embedding-unavailable");
    }

    @Test
    void infoExposesVersions() {
        var r = Http.get(port, "/actuator/info", KEY);
        assertThat(r.status()).isEqualTo(200);
        JsonNode rag = JSON.readTree(r.body()).get("rag");
        assertThat(rag.get("chunkerVersion").asString()).isEqualTo("fixed-512-64-cl100k");
        assertThat(rag.get("embeddingModel").asString()).isEqualTo("fake/hash");
        assertThat(rag.get("corpusVersion").asString()).isEqualTo("abcdef123456");
        assertThat(rag.get("index").asString()).isEqualTo("t_search-chunks-fake8-fixed512-v1");
    }

    @Test
    void infoRequiresKey() {
        assertThat(Http.get(port, "/actuator/info", null).status()).isEqualTo(401);
    }
}
