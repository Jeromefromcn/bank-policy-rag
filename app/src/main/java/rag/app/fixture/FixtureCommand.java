package rag.app.fixture;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import rag.app.RagProperties;
import rag.app.embed.Embedder;
import rag.app.embed.EmbeddingPrefixes;
import rag.app.embed.FixtureEmbedder;
import rag.core.chunk.Chunk;
import rag.core.chunk.Chunker;
import rag.core.chunk.FixedChunker;
import rag.core.chunk.StructuralChunker;
import rag.core.chunk.TokenCounter;
import rag.core.corpus.CorpusLoader;
import rag.core.corpus.CorpusVersion;
import rag.core.eval.EvalScope;
import rag.core.eval.GoldenItem;
import rag.core.eval.GoldenSet;
import rag.core.model.ParsedDocument;

/**
 * {@code fixture --out=eval/fixtures [--golden=…] [--chunker=…]} (spec 05 §7.1): chunks the Example Bank
 * corpus, embeds the chunks and scored golden questions with the real model, and writes vector-only files.
 * It does not touch Elasticsearch.
 */
@Component
public class FixtureCommand implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(FixtureCommand.class);

    private final RagProperties props;
    private final Embedder embedder;

    public FixtureCommand(RagProperties props, Embedder embedder) {
        this.props = props;
        this.embedder = embedder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (args.getNonOptionArgs().isEmpty() || !args.getNonOptionArgs().getFirst().equals("fixture")) {
            return;
        }
        if (embedder instanceof FixtureEmbedder) {
            throw new IllegalStateException("build-fixture needs the real embedding model; unset rag.embedding.fixture-dir");
        }
        String out = args.containsOption("out") ? args.getOptionValues("out").getFirst() : "eval/fixtures";
        String goldenPath = args.containsOption("golden") ? args.getOptionValues("golden").getFirst()
                : "eval/golden-set.yaml";
        String kind = args.containsOption("chunker") ? args.getOptionValues("chunker").getFirst() : props.chunking().kind();
        boolean structural = kind.equals("structural");
        if (!structural && !kind.equals("fixed")) {
            throw new IllegalArgumentException("--chunker must be fixed or structural, got " + kind);
        }

        Path root = Path.of(props.corpus().root());
        Chunker chunker = structural
                ? new StructuralChunker(TokenCounter.cl100k(), props.chunking().structural().maxTokens(),
                        props.chunking().structural().minTokens())
                : new FixedChunker(TokenCounter.cl100k(), props.chunking().maxTokens(),
                        props.chunking().overlapTokens());
        var spec = props.embedding().models().get(props.embedding().model());
        if (spec == null) {
            throw new IllegalArgumentException("unknown embedding model '" + props.embedding().model() + "'");
        }

        List<ParsedDocument> docs = new CorpusLoader(root).load(CorpusLoader.Scope.EXAMPLE_BANK);
        Set<String> exampleBank = CorpusLoader.knownDocIds(root, CorpusLoader.Scope.EXAMPLE_BANK);
        List<GoldenItem> golden = GoldenSet.load(Path.of(goldenPath), CorpusLoader.knownDocIds(root));
        List<GoldenItem> scored = golden.stream()
                .filter(i -> EvalScope.inExampleBank(i, exampleBank))
                .filter(i -> !i.category().equals("unanswerable"))
                .toList();

        List<Chunk> chunks = docs.stream().flatMap(d -> chunker.chunk(d).stream()).toList();
        List<String> prefixedChunks = chunks.stream()
                .map(c -> EmbeddingPrefixes.document(spec, c.content())).toList();
        List<float[]> chunkVectors = embedder.embedDocuments(chunks.stream().map(Chunk::content).toList());
        if (chunkVectors.size() != chunks.size()) {
            throw new IllegalStateException("expected " + chunks.size() + " chunk vectors, got " + chunkVectors.size());
        }
        List<FixtureFiles.FixtureChunk> fixtures = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            fixtures.add(new FixtureFiles.FixtureChunk(c.chunkId(), c.contentHash(),
                    FixtureFiles.textHash(prefixedChunks.get(i)), chunkVectors.get(i)));
        }

        List<FixtureFiles.FixtureQuestion> questions = new ArrayList<>();
        for (GoldenItem item : scored) {
            String prefixed = EmbeddingPrefixes.query(spec, item.question());
            questions.add(new FixtureFiles.FixtureQuestion(item.id(), FixtureFiles.textHash(prefixed),
                    embedder.embedQuery(item.question())));
        }

        var manifest = new FixtureFiles.FixtureManifest(CorpusVersion.compute(root), embedder.modelId(),
                chunker.version(), chunks.size(), questions.size(), Map.of());
        Path dir = FixtureFiles.write(Path.of(out), manifest, fixtures, questions);
        LOG.info("fixture: {} documents, {} chunks, {} questions into {}", docs.size(), chunks.size(), questions.size(),
                dir);
    }
}
