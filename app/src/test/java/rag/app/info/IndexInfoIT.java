package rag.app.info;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import rag.app.es.ChunkIndexAdmin;
import rag.app.es.ChunkWriter;
import rag.app.support.Http;
import rag.app.support.IntegrationTest;
import rag.app.support.TestIndex;
import tools.jackson.databind.json.JsonMapper;

/** An ablation run points the app at one physical index; /v1/info must describe that index, not the alias. */
@TestPropertySource(properties = {"rag.env-prefix=t_info", "rag.retrieval.chunks-index=t_info-chunks-fake8-fixed512-v1",
        "rag.ask.min-similarity=0.42", "spring.ai.deepseek.chat.options.model=test-gen-model"})
class IndexInfoIT extends IntegrationTest {

    @Autowired ChunkIndexAdmin admin;
    @Autowired ChunkWriter writer;

    @BeforeEach
    void index() {
        TestIndex.rebuild(admin, writer, "t_info", TestIndex.mixed("tolerance for disruption"));
    }

    @Test
    void describesTheConfiguredPhysicalIndex() {
        var r = Http.get(port, "/v1/info", KEY);
        var rag = JsonMapper.builder().build().readTree(r.body());
        assertThat(rag.get("index").asString()).isEqualTo("t_info-chunks-fake8-fixed512-v1");
        assertThat(rag.get("chunkerVersion").asString()).isEqualTo("fixed-512-64-cl100k");
    }

    @Test
    void describesGenerationSettingsSoReportsRecordThem() {
        var r = Http.get(port, "/v1/info", KEY);
        var rag = JsonMapper.builder().build().readTree(r.body());
        assertThat(rag.get("generationModel").asString()).isEqualTo("test-gen-model");
        assertThat(rag.get("answerPrompt").asString()).isEqualTo("answer.v2");
        assertThat(rag.get("minSimilarity").asString()).isEqualTo("0.42");
    }
}
