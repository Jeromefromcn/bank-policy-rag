package rag.core.ids;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import rag.core.model.SectionPath;

class HashesTest {

    @Test
    void idsAreSixteenHexAndStable() {
        assertThat(Hashes.chunkId("abc", 0, "v")).hasSize(16).matches("[0-9a-f]+")
                .isEqualTo(Hashes.chunkId("abc", 0, "v"))
                .isNotEqualTo(Hashes.chunkId("abc", 1, "v"))
                .isNotEqualTo(Hashes.chunkId("abc", 0, "w"));
        assertThat(Hashes.sectionId("d", SectionPath.parse("3.2"))).hasSize(16)
                .isNotEqualTo(Hashes.sectionId("d", SectionPath.parse("3.20")))
                .isNotEqualTo(Hashes.sectionId("e", SectionPath.parse("3.2")));
    }

    @Test
    void sha256HexOfKnownInput() {
        assertThat(Hashes.sha256Hex("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void sectionIdUsesDocIdHashLeaf() {
        assertThat(Hashes.sectionId("d", SectionPath.parse("3.2"))).isEqualTo(Hashes.sha256Hex("d#3.2").substring(0, 16));
    }
}
