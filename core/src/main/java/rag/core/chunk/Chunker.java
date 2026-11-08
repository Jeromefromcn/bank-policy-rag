package rag.core.chunk;

import java.util.List;
import rag.core.model.ParsedDocument;

/** A deterministic chunking strategy. {@link #version()} is part of every chunk id (ROADMAP §4.2). */
public interface Chunker {

    String version();

    List<Chunk> chunk(ParsedDocument doc);
}
