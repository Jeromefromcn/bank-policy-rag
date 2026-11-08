package rag.app.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import rag.app.es.ChunkIndexAdmin;
import rag.app.support.EsAdminHttp;
import rag.app.support.IntegrationTest;
import rag.core.chunk.FixedChunker;
import rag.core.chunk.TokenCounter;
import rag.core.corpus.CorpusLoader;
import rag.core.corpus.TestCorpus;

class IngestIT extends IntegrationTest {

    static final String PREFIX = "t_ingest";
    static final String ALIAS = PREFIX + "-chunks";
    static final String INDEX = PREFIX + "-chunks-fake8-fixed512-v1";
    static final Path WORK = tempDir();
    static final Path CORPUS = createCorpus();

    @DynamicPropertySource
    static void corpus(DynamicPropertyRegistry r) {
        r.add("rag.env-prefix", () -> PREFIX);
        r.add("rag.corpus.root", CORPUS::toString);
        r.add("rag.embedding.cache-dir", () -> WORK.resolve("cache").toString());
    }

    @Autowired IngestCommand ingest;
    @Autowired ChunkIndexAdmin admin;
    @Autowired ElasticsearchClient client;

    @BeforeEach
    void clean() throws IOException {
        if (admin.exists(INDEX)) {
            admin.delete(INDEX);
        }
        Path cache = WORK.resolve("cache");
        if (Files.exists(cache)) {
            try (var s = Files.walk(cache)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    void run(String... args) {
        ingest.run(new DefaultApplicationArguments(args));
    }

    @Test
    void ingestCreatesIndexAliasAndMeta() throws IOException {
        run("ingest");
        var info = admin.aliasInfo(ALIAS).orElseThrow();
        assertThat(info.index()).isEqualTo(INDEX);
        assertThat(info.meta()).containsEntry("chunkerVersion", "fixed-512-64-cl100k")
                .containsEntry("embeddingModel", "fake/hash")
                .containsKey("corpusVersion");
        client.indices().refresh(r -> r.index(ALIAS));
        assertThat(client.count(c -> c.index(ALIAS)).count()).isEqualTo(expectedChunkCount());
        String mapping = EsAdminHttp.get("/" + INDEX + "/_mapping");
        assertThat(mapping).contains("\"type\":\"hnsw\"").contains("\"m\":16").contains("\"ef_construction\":100")
                .contains("\"coveredSections\"").contains("\"analyzer\":\"english\"");
        assertThat(EsAdminHttp.get("/" + INDEX + "/_settings")).contains("\"number_of_replicas\":\"0\"");
    }

    @Test
    void reingestIsDeterministic() throws IOException {
        run("ingest");
        var first = chunkIdsAndHashes();
        run("ingest", "--recreate");
        assertThat(chunkIdsAndHashes()).isEqualTo(first).isNotEmpty();
    }

    @Test
    void existingIndexWithoutRecreateFails() {
        run("ingest");
        assertThatThrownBy(() -> run("ingest")).hasMessageContaining(INDEX).hasMessageContaining("--recreate");
    }

    @Test
    void indexStepFailsOnCacheMiss() {
        assertThatThrownBy(() -> run("ingest", "--step=index")).hasMessageContaining("cache");
        assertThat(admin.exists(INDEX)).isFalse();
    }

    @Test
    void embedStepThenIndexStep() {
        run("ingest", "--step=embed");
        assertThat(admin.exists(INDEX)).isFalse();
        run("ingest", "--step=index");
        assertThat(admin.exists(INDEX)).isTrue();
    }

    @Test
    void indexedChunkCarriesClassificationAndSections() throws IOException {
        run("ingest");
        String hits = EsAdminHttp.get("/" + ALIAS + "/_search?q=docId:eb-a&_source_excludes=embedding");
        assertThat(hits).contains("\"classification\":\"internal\"").contains("\"coveredSections\":[\"1\"]")
                .contains("\"sourceType\":\"internal\"");
    }

    private Set<String> chunkIdsAndHashes() throws IOException {
        client.indices().refresh(r -> r.index(ALIAS));
        var res = client.search(s -> s.index(ALIAS).size(1000).source(src -> src.filter(f -> f.includes("chunkId", "contentHash"))),
                java.util.Map.class);
        Set<String> out = new TreeSet<>();
        res.hits().hits().forEach(h -> out.add(h.source().get("chunkId") + ":" + h.source().get("contentHash")));
        return out;
    }

    private static long expectedChunkCount() {
        var chunker = new FixedChunker(TokenCounter.cl100k(), 512, 64);
        return new CorpusLoader(CORPUS).load().stream().mapToLong(d -> chunker.chunk(d).size()).sum();
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("ingest-it");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path createCorpus() {
        try {
            return TestCorpus.create(WORK);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
