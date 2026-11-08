package rag.app.es;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import rag.app.support.EsAdminHttp;
import rag.app.support.IntegrationTest;

class SectionIndexIT extends IntegrationTest {

    static final String ALIAS = "t_sections-sections";
    static final String INDEX = "t_sections-sections-struct400-v1";

    @Autowired ChunkIndexAdmin admin;
    @Autowired SectionWriter writer;

    static SectionDocument section(String id, String docId, String classification, String content) {
        return new SectionDocument(id, docId, "Title " + docId, "1", "internal", List.of("3"), List.of(7, 8), classification,
                content, "abcdef123456");
    }

    @Test
    void createsStrictMappingWithMetaAndStoresParents() {
        if (admin.exists(INDEX)) {
            admin.delete(INDEX);
        }
        admin.createSections(INDEX, Map.of("corpusVersion", "abcdef123456", "chunkerVersion", "structural-v1-400-cl100k"));

        writer.bulkIndex(INDEX, List.of(section("s1", "eb-a", "internal", "full text of section three"),
                section("s2", "spm-x", "public", "another section")));
        admin.pointAlias(ALIAS, INDEX);

        String mapping = EsAdminHttp.get("/" + INDEX + "/_mapping");
        assertThat(mapping).contains("\"dynamic\":\"strict\"").contains("\"analyzer\":\"english\"")
                .contains("\"chunkerVersion\":\"structural-v1-400-cl100k\"").doesNotContain("dense_vector");
        assertThat(EsAdminHttp.get("/" + ALIAS + "/_doc/s1")).contains("full text of section three").contains("\"pages\":[7,8]");
        assertThat(EsAdminHttp.get("/" + ALIAS + "/_count")).contains("\"count\":2");
    }

    @Test
    void anUnknownFieldIsRejectedByTheStrictMapping() {
        String index = "t_sections-sections-strict-v1";
        if (admin.exists(index)) {
            admin.delete(index);
        }
        admin.createSections(index, Map.of("corpusVersion", "x", "chunkerVersion", "y"));

        assertThat(EsAdminHttp.post("/" + index + "/_doc", "{\"surprise\":1}")).contains("strict_dynamic_mapping_exception");
    }
}
