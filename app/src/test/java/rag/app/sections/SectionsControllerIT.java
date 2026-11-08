package rag.app.sections;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import rag.app.es.ChunkIndexAdmin;
import rag.app.es.IndexNames;
import rag.app.es.SectionDocument;
import rag.app.es.SectionWriter;
import rag.app.support.Http;
import rag.app.support.IntegrationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@TestPropertySource(properties = "rag.env-prefix=t_sec")
class SectionsControllerIT extends IntegrationTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String INDEX = "t_sec-sections-struct400-v1";

    @Autowired ChunkIndexAdmin admin;
    @Autowired SectionWriter writer;

    @BeforeEach
    void index() {
        if (admin.exists(INDEX)) {
            admin.delete(INDEX);
        }
        admin.createSections(INDEX, Map.of("corpusVersion", "abcdef123456", "chunkerVersion", "structural-v1-400-cl100k"));
        writer.bulkIndex(INDEX, List.of(
                new SectionDocument("sec-public", "spm-x", "Title spm-x", "1", "regulatory", List.of("3"), List.of(7, 8), "public",
                        "full text of the public section", "abcdef123456"),
                new SectionDocument("sec-internal", "eb-a", "Title eb-a", "1", "internal", List.of("2"), List.of(), "internal",
                        "full text of the internal section", "abcdef123456")));
        admin.pointAlias(IndexNames.sectionsAlias("t_sec"), INDEX);
    }

    @Test
    void returnsTheParentForAVisibleId() {
        var r = Http.get(port, "/v1/sections/sec-public", PUBLIC_KEY);

        assertThat(r.status()).as(r.body()).isEqualTo(200);
        JsonNode body = JSON.readTree(r.body());
        assertThat(body.get("sectionId").asString()).isEqualTo("sec-public");
        assertThat(body.get("docId").asString()).isEqualTo("spm-x");
        assertThat(body.get("docTitle").asString()).isEqualTo("Title spm-x");
        assertThat(body.get("sectionPath").get(0).asString()).isEqualTo("3");
        assertThat(body.get("pages").size()).isEqualTo(2);
        assertThat(body.get("classification").asString()).isEqualTo("public");
        assertThat(body.get("content").asString()).isEqualTo("full text of the public section");
    }

    @Test
    void aClearedCallerSeesTheInternalSection() {
        assertThat(Http.get(port, "/v1/sections/sec-internal", KEY).status()).isEqualTo(200);
    }

    @Test
    void inaccessibleAndUnknownAreIndistinguishable() {
        var inaccessible = Http.get(port, "/v1/sections/sec-internal", PUBLIC_KEY);
        var unknown = Http.get(port, "/v1/sections/no-such-section", PUBLIC_KEY);

        assertThat(inaccessible.status()).isEqualTo(404);
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(inaccessible.contentType()).startsWith("application/problem+json");
        JsonNode a = JSON.readTree(inaccessible.body());
        JsonNode b = JSON.readTree(unknown.body());
        assertThat(a.get("type").asString()).isEqualTo("urn:rag:problem:not-found");
        assertThat(a.get("title")).isEqualTo(b.get("title"));
        assertThat(a.get("detail")).isEqualTo(b.get("detail"));
        assertThat(a.get("status")).isEqualTo(b.get("status"));
        assertThat(a.has("instance") ? a.get("instance").asString().replaceAll("[^/]*$", "")
                : "").isEqualTo(b.has("instance") ? b.get("instance").asString().replaceAll("[^/]*$", "") : "");
    }

    @Test
    void requiresAKey() {
        assertThat(Http.get(port, "/v1/sections/sec-public", null).status()).isEqualTo(401);
    }

    @Test
    void withoutASectionsIndexEveryIdIs404() {
        admin.delete(INDEX);
        assertThat(Http.get(port, "/v1/sections/sec-public", KEY).status()).isEqualTo(404);
    }
}
