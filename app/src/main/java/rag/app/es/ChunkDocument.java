package rag.app.es;

import java.time.Instant;
import java.util.List;
import rag.core.chunk.Chunk;
import rag.core.model.ParsedDocument;

/** The {@code chunks} index document (ROADMAP §4.2 plus {@code coveredSections}, {@code docVersion}). */
public record ChunkDocument(
        String chunkId,
        String sectionId,
        String docId,
        String docTitle,
        String docVersion,
        String sourceType,
        List<String> sectionPath,
        List<String> coveredSections,
        List<Integer> pages,
        String classification,
        String corpusVersion,
        int ordinal,
        String content,
        String contentHash,
        int tokenCount,
        float[] embedding,
        String embeddingModel,
        String chunkerVersion,
        String ingestedAt) {

    public static ChunkDocument of(Chunk c, ParsedDocument d, float[] embedding, String corpusVersion,
            String chunkerVersion, String embeddingModel, Instant ingestedAt) {
        return new ChunkDocument(c.chunkId(), c.sectionId(), c.docId(), d.title(), d.docVersion(), d.sourceType().wire(),
                c.sectionPath().components(), c.coveredSections(), c.pages(), d.classification().wire(), corpusVersion,
                c.ordinal(), c.content(), c.contentHash(), c.tokenCount(), embedding, embeddingModel, chunkerVersion,
                ingestedAt.toString());
    }
}
