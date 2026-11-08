package rag.app.ingest;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import rag.app.RagProperties;
import rag.app.embed.Embedder;
import rag.app.embed.EmbeddingCache;
import rag.app.es.ChunkDocument;
import rag.app.es.ChunkIndexAdmin;
import rag.app.es.ChunkWriter;
import rag.app.es.SectionDocument;
import rag.app.es.SectionWriter;
import rag.app.es.IndexNames;
import rag.core.chunk.Chunk;
import rag.core.chunk.Chunker;
import rag.core.chunk.SectionParents;
import rag.core.chunk.StructuralChunker;
import rag.core.chunk.FixedChunker;
import rag.core.chunk.TokenCounter;
import rag.core.corpus.CorpusLoader;
import rag.core.corpus.CorpusVersion;
import rag.core.model.ParsedDocument;

/**
 * {@code ingest [--step=all|embed|index] [--chunker=fixed|structural] [--recreate] [--promote]} (specs 01 §3, 02 §6). The embed step only fills the
 * embedding cache; the index step reads only from it, so Ollama inference and HNSW building never
 * compete for CPU (ROADMAP §7.1). The embedding model is the process's configured one
 * ({@code RAG_EMBEDDING_MODEL}). The aliases move only with {@code --promote}, so experiments never
 * change what the running service reads.
 */
@Component
public class IngestCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestCommand.class);
    static final int EMBED_BATCH = 64;
    static final int INDEX_VERSION = 1;

    private record Item(ParsedDocument doc, Chunk chunk) {}

    private final RagProperties props;
    private final Embedder embedder;
    private final EmbeddingCache cache;
    private final ChunkIndexAdmin admin;
    private final ChunkWriter writer;
    private final SectionWriter sectionWriter;

    public IngestCommand(RagProperties props, Embedder embedder, EmbeddingCache cache, ChunkIndexAdmin admin, ChunkWriter writer,
            SectionWriter sectionWriter) {
        this.props = props;
        this.embedder = embedder;
        this.cache = cache;
        this.admin = admin;
        this.writer = writer;
        this.sectionWriter = sectionWriter;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (args.getNonOptionArgs().isEmpty() || !args.getNonOptionArgs().getFirst().equals("ingest")) {
            return;
        }
        String step = args.containsOption("step") ? args.getOptionValues("step").getFirst() : "all";
        if (!List.of("all", "embed", "index").contains(step)) {
            throw new IllegalArgumentException("--step must be all, embed or index, got " + step);
        }
        boolean recreate = args.containsOption("recreate");
        boolean promote = args.containsOption("promote");
        String kind = args.containsOption("chunker") ? args.getOptionValues("chunker").getFirst() : props.chunking().kind();
        boolean structural = kind.equals("structural");
        if (!structural && !kind.equals("fixed")) {
            throw new IllegalArgumentException("--chunker must be fixed or structural, got " + kind);
        }

        Path root = Path.of(props.corpus().root());
        Chunker chunker = structural
                ? new StructuralChunker(TokenCounter.cl100k(), props.chunking().structural().maxTokens(), props.chunking().structural().minTokens())
                : new FixedChunker(TokenCounter.cl100k(), props.chunking().maxTokens(), props.chunking().overlapTokens());
        String index = IndexNames.chunksIndex(props.envPrefix(), embedder.shortName(), embedder.dims(), chunker.version(), INDEX_VERSION);
        String sectionsIndex = structural ? IndexNames.sectionsIndex(props.envPrefix(), chunker.version(), INDEX_VERSION) : null;
        boolean indexing = !step.equals("embed");
        if (indexing && !recreate) {
            for (String name : structural ? List.of(index, sectionsIndex) : List.of(index)) {
                if (admin.exists(name)) {
                    throw new IllegalStateException("index " + name + " already exists; pass --recreate to rebuild it");
                }
            }
        }

        long t0 = System.nanoTime();
        List<ParsedDocument> docs = new CorpusLoader(root).load();
        String corpusVersion = CorpusVersion.compute(root);
        List<Item> items = new ArrayList<>();
        for (ParsedDocument d : docs) {
            chunker.chunk(d).forEach(c -> items.add(new Item(d, c)));
        }
        long tokens = items.stream().mapToLong(i -> i.chunk().tokenCount()).sum();
        int[] sizes = items.stream().mapToInt(i -> i.chunk().tokenCount()).sorted().toArray();
        log.info("ingest: chunker {}, {} documents, {} chunks, {} tokens, corpusVersion {}, parse+chunk {} ms",
                chunker.version(), docs.size(), items.size(), tokens, corpusVersion, ms(t0));
        if (sizes.length > 0) {
            log.info("ingest: chunk tokens min {} / median {} / max {}", sizes[0], sizes[sizes.length / 2], sizes[sizes.length - 1]);
        }

        if (!step.equals("index")) {
            embed(items);
        }
        if (indexing) {
            index(items, index, corpusVersion, chunker.version(), recreate, promote);
            if (structural) {
                indexSections(docs, sectionsIndex, corpusVersion, chunker.version(), recreate, promote);
            }
        }
    }

    private void embed(List<Item> items) {
        long t0 = System.nanoTime();
        Map<String, String> missing = new LinkedHashMap<>();   // contentHash -> content, deduplicated
        for (Item i : items) {
            if (cache.get(embedder.modelId(), i.chunk().contentHash()).isEmpty()) {
                missing.putIfAbsent(i.chunk().contentHash(), i.chunk().content());
            }
        }
        log.info("embed: {} cache hits, {} to embed with {}", items.size() - missing.size(), missing.size(), embedder.modelId());
        List<Map.Entry<String, String>> todo = new ArrayList<>(missing.entrySet());
        for (int i = 0; i < todo.size(); i += EMBED_BATCH) {
            var batch = todo.subList(i, Math.min(todo.size(), i + EMBED_BATCH));
            List<float[]> vectors = embedder.embedDocuments(batch.stream().map(Map.Entry::getValue).toList());
            for (int k = 0; k < batch.size(); k++) {
                cache.put(embedder.modelId(), batch.get(k).getKey(), vectors.get(k));
            }
            log.info("embed: {}/{}", Math.min(todo.size(), i + EMBED_BATCH), todo.size());
        }
        log.info("embed step: {} ms", ms(t0));
    }

    private void index(List<Item> items, String index, String corpusVersion, String chunkerVersion, boolean recreate,
            boolean promote) {
        long t0 = System.nanoTime();
        Instant now = Instant.now();
        List<ChunkDocument> docs = new ArrayList<>(items.size());
        int misses = 0;
        for (Item i : items) {
            var v = cache.get(embedder.modelId(), i.chunk().contentHash());
            if (v.isEmpty()) {
                misses++;
                continue;
            }
            docs.add(ChunkDocument.of(i.chunk(), i.doc(), v.get(), corpusVersion, chunkerVersion, embedder.modelId(), now));
        }
        if (misses > 0) {
            throw new IllegalStateException(misses + " chunks are missing from the embedding cache; run ingest --step=embed first");
        }
        if (recreate && admin.exists(index)) {
            admin.delete(index);
        }
        admin.create(index, embedder.dims(), Map.of(
                "corpusVersion", corpusVersion, "chunkerVersion", chunkerVersion, "embeddingModel", embedder.modelId()));
        writer.bulkIndex(index, docs);
        if (promote) {
            admin.pointAlias(IndexNames.chunksAlias(props.envPrefix()), index);
        }
        log.info("index step: {} chunks into {}{} ({} ms)", docs.size(), index, promote ? " (promoted)" : "", ms(t0));
    }

    private void indexSections(List<ParsedDocument> docs, String index, String corpusVersion, String chunkerVersion,
            boolean recreate, boolean promote) {
        long t0 = System.nanoTime();
        List<SectionDocument> sections = new ArrayList<>();
        for (ParsedDocument d : docs) {
            SectionParents.of(d).forEach(p -> sections.add(SectionDocument.of(p, d, corpusVersion)));
        }
        if (recreate && admin.exists(index)) {
            admin.delete(index);
        }
        admin.createSections(index, Map.of("corpusVersion", corpusVersion, "chunkerVersion", chunkerVersion));
        sectionWriter.bulkIndex(index, sections);
        if (promote) {
            admin.pointAlias(IndexNames.sectionsAlias(props.envPrefix()), index);
        }
        log.info("index step: {} sections into {}{} ({} ms)", sections.size(), index, promote ? " (promoted)" : "", ms(t0));
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
