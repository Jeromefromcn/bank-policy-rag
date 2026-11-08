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
}
