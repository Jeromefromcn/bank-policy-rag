package rag.app.es;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IndexNamesTest {

    @Test
    void physicalName() {
        assertThat(IndexNames.chunksIndex("rag_dev", "nomic", 768, "fixed-512-64-cl100k", 1)).isEqualTo("rag_dev-chunks-nomic768-fixed512-v1");
    }

    @Test
    void alias() {
        assertThat(IndexNames.chunksAlias("rag")).isEqualTo("rag-chunks");
    }

    @Test
    void structuralChunkIndexNames() {
        assertThat(IndexNames.chunksIndex("rag_dev", "nomic", 768, "structural-v1-400-cl100k", 1)).isEqualTo("rag_dev-chunks-nomic768-struct400-v1");
        assertThat(IndexNames.chunksIndex("rag_dev", "bgesmall", 384, "structural-v1-400-cl100k", 1)).isEqualTo("rag_dev-chunks-bgesmall384-struct400-v1");
        assertThat(IndexNames.chunksIndex("rag_dev", "bgem3", 1024, "structural-v1-400-cl100k", 1)).isEqualTo("rag_dev-chunks-bgem3-1024-struct400-v1");
    }

    @Test
    void sectionsNames() {
        assertThat(IndexNames.sectionsAlias("rag_dev")).isEqualTo("rag_dev-sections");
        assertThat(IndexNames.sectionsIndex("rag_dev", "structural-v1-400-cl100k", 1)).isEqualTo("rag_dev-sections-struct400-v1");
    }

    @Test
    void minimumOverrideKeepsDifferentChunkerNamesApart() {
        assertThat(IndexNames.chunksIndex("rag_dev", "nomic", 768, "structural-v1-400-cl100k-min40", 1))
                .isNotEqualTo(IndexNames.chunksIndex("rag_dev", "nomic", 768, "structural-v1-400-cl100k", 1));
    }
}
