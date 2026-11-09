package rag.app.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import java.util.List;
import java.util.Map;
import java.util.Set;
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
import rag.app.support.StubReranker;
import rag.app.security.Principal;
import rag.core.model.Clearance;
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
    @Autowired StubReranker reranker;
    @Autowired HybridSearch hybrid;

    @BeforeEach
    void index() {
        reranker.reset();
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
        assertThat(first.get("scores").get("bm25Rank").asInt()).isEqualTo(1);
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
    void everyModeReturnsHitsWithItsOwnScores() {
        var vector = JSON.readTree(post(Map.of("query", QUERY, "mode", "VECTOR")).body()).get("hits").get(0).get("scores");
        var bm25 = JSON.readTree(post(Map.of("query", QUERY, "mode", "BM25")).body()).get("hits").get(0).get("scores");
        var hybrid = JSON.readTree(post(Map.of("query", QUERY, "mode", "HYBRID")).body()).get("hits").get(0).get("scores");

        assertThat(vector.has("vectorRank")).isTrue();
        assertThat(vector.has("bm25Rank")).isFalse();
        assertThat(bm25.has("bm25Rank")).isTrue();
        assertThat(bm25.has("vectorRank")).isFalse();
        assertThat(hybrid.has("vectorRank") && hybrid.has("bm25Rank")).isTrue();
    }

    @Test
    void modeDefaultsToHybrid() {
        var scores = JSON.readTree(post(Map.of("query", QUERY)).body()).get("hits").get(0).get("scores");
        assertThat(scores.has("vectorRank") && scores.has("bm25Rank")).isTrue();
    }

    @Test
    void unknownModeIsRejected() {
        assertProblem(post(Map.of("query", "x", "mode", "FUZZY")), 400, "urn:rag:problem:unsupported-mode");
    }

    @Test
    void rerankTrueReordersByStubScores() {
        reranker.scoreWith((q, p) -> p.equals("outsourcing agreements") ? 8.0 : -2.0);
        var r = post(Map.of("query", QUERY, "topK", 3, "rerank", true));
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        JsonNode body = JSON.readTree(r.body());
        JsonNode first = body.get("hits").get(0);
        assertThat(first.get("chunkId").asString()).isEqualTo("c-p6");
        assertThat(first.get("scores").get("rerankScore").asDouble()).isEqualTo(8.0);
        assertThat(first.get("scores").has("fusedRank")).isTrue();
        assertThat(body.get("hits").size()).isEqualTo(3);
        JsonNode diag = body.get("diagnostics");
        assertThat(diag.get("bestRerankProbability").asDouble()).isGreaterThan(0.999);
        assertThat(diag.get("latencyMs").has("rerank")).isTrue();
        assertThat(reranker.calls()).singleElement().satisfies(c -> assertThat(c.passages()).hasSize(7));
    }

    @Test
    void publicCallerRerankRequestHasNoInternalChunk() {
        var r = Http.post(port, "/v1/search", PUBLIC_KEY, JSON.writeValueAsString(Map.of("query", QUERY, "rerank", true)));
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(reranker.calls()).singleElement().satisfies(c -> {
            assertThat(c.passages()).isNotEmpty().doesNotContain(QUERY);
            assertThat(c.passages()).hasSize(6);
        });
    }

    @Test
    void rerankerDownIs503() {
        reranker.failNext();
        assertProblem(post(Map.of("query", QUERY, "rerank", true)), 503, "urn:rag:problem:reranker-unavailable");
    }

    @Test
    void rerankFalseResponseMatchesPhase3() {
        var principal = new Principal("tester", Clearance.RESTRICTED, Set.of());
        var direct = hybrid.searchWithDiagnostics(QUERY, 5, Mode.HYBRID, principal);
        JsonNode body = JSON.readTree(post(Map.of("query", QUERY, "rerank", false)).body());
        JsonNode viaApi = body.get("hits");
        assertThat(viaApi.size()).isEqualTo(direct.hits().size());
        for (int i = 0; i < viaApi.size(); i++) {
            JsonNode h = viaApi.get(i);
            SearchHit d = direct.hits().get(i);
            assertThat(h.get("chunkId").asString()).isEqualTo(d.chunkId());
            assertThat(h.get("score").asDouble()).isEqualTo(d.score());
            assertThat(h.get("scores").has("rerankScore")).isFalse();
        }
        assertThat(body.get("diagnostics").get("bestVectorSimilarity").asDouble()).isEqualTo(direct.bestVectorSimilarity());
        assertThat(body.get("diagnostics").get("bestRerankProbability").isNull()).isTrue();
        assertThat(body.get("diagnostics").get("latencyMs").get("rerank").asLong()).isZero();
        assertThat(reranker.calls()).isEmpty();
    }

    @Test
    void bm25WorksWithoutEmbedder() {
        doThrow(new EmbeddingUnavailableException("down", null)).when(embedder).embedQuery(anyString());
        var r = post(Map.of("query", QUERY, "mode", "BM25"));
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(JSON.readTree(r.body()).get("hits").size()).isPositive();
    }

    @Test
    void hybridFailsWhenEmbedderDown() {
        doThrow(new EmbeddingUnavailableException("down", null)).when(embedder).embedQuery(anyString());
        assertProblem(post(Map.of("query", "x", "mode", "HYBRID")), 503, "urn:rag:problem:embedding-unavailable");
    }

    @Test
    void embeddingFailureReturns503() {
        doThrow(new EmbeddingUnavailableException("down", null)).when(embedder).embedQuery(anyString());
        assertProblem(post(Map.of("query", "x", "mode", "VECTOR")), 503, "urn:rag:problem:embedding-unavailable");
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

    @Test
    void responseCarriesBestVectorSimilarity() {
        var r = post(Map.of("query", QUERY, "mode", "HYBRID"));
        assertThat(r.status()).isEqualTo(200);
        assertThat(JSON.readTree(r.body()).get("diagnostics").get("bestVectorSimilarity").asDouble()).isGreaterThan(0.99);
    }

    @Test
    void bestVectorSimilarityIsNullInBm25Mode() {
        var r = post(Map.of("query", QUERY, "mode", "BM25"));
        assertThat(r.status()).isEqualTo(200);
        assertThat(JSON.readTree(r.body()).get("diagnostics").get("bestVectorSimilarity").isNull()).isTrue();
    }
}
