package rag.app.support;

import java.util.List;
import java.util.Map;
import rag.app.es.ChunkDocument;
import rag.app.es.ChunkIndexAdmin;
import rag.app.es.ChunkWriter;
import rag.app.es.IndexNames;

/** Builds a small chunks index (self-made text, FakeEmbedder vectors) behind {@code <prefix>-chunks}. */
public final class TestIndex {

    public static final Map<String, String> META =
            Map.of("corpusVersion", "abcdef123456", "chunkerVersion", "fixed-512-64-cl100k", "embeddingModel", "fake/hash");

    private TestIndex() {}

    public static void rebuild(ChunkIndexAdmin admin, ChunkWriter writer, String prefix, List<ChunkDocument> docs) {
        String index = IndexNames.chunksIndex(prefix, "fake", TestBeans.DIMS, "fixed-512-64-cl100k", 1);
        if (admin.exists(index)) {
            admin.delete(index);
        }
        admin.create(index, TestBeans.DIMS, META);
        writer.bulkIndex(index, docs);
        admin.pointAlias(IndexNames.chunksAlias(prefix), index);
    }

    public static ChunkDocument chunk(String chunkId, String docId, String classification, String section, String content) {
        return new ChunkDocument(chunkId, "s-" + chunkId, docId, "Title " + docId, "1", "internal", List.of(section),
                List.of(section), List.of(), classification, "abcdef123456", 0, content, "h" + chunkId, 5,
                new FakeEmbedder(TestBeans.DIMS).vector(content), "fake/hash", "fixed-512-64-cl100k", "2026-11-08T00:00:00Z");
    }

    /** One internal chunk identical to {@code query}, six public chunks that only partly overlap it. */
    public static List<ChunkDocument> mixed(String query) {
        return List.of(
                chunk("c-int", "eb-a", "internal", "2", query),
                chunk("c-p1", "spm-x", "public", "1", "tolerance of operations"),
                chunk("c-p2", "spm-x", "public", "1.1", "disruption scenarios and testing"),
                chunk("c-p3", "spm-x", "public", "2", "board approval of tolerance"),
                chunk("c-p4", "spm-x", "public", "3", "critical operations mapping"),
                chunk("c-p5", "spm-x", "public", "4", "incident response for disruption"),
                chunk("c-p6", "spm-x", "public", "5", "outsourcing agreements"));
    }

    /** A public, an internal and a restricted chunk; only the restricted one matches {@code query} exactly. */
    public static List<ChunkDocument> threeLevels(String query) {
        return List.of(
                chunk("c-pub", "spm-x", "public", "1", "public tolerance guidance"),
                chunk("c-int", "eb-a", "internal", "2", "internal change process"),
                chunk("c-res", "eb-secret", "restricted", "9", query));
    }
}
