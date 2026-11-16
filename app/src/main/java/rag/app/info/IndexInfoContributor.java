package rag.app.info;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;
import rag.app.RagProperties;
import org.springframework.core.env.Environment;
import rag.app.ask.PromptRenderer;
import rag.app.es.ChunkIndexAdmin;

/** Exposes the versions of the index searches read (the alias, or the configured physical index) so eval reports can record them (spec 01 §4). */
@Component
public class IndexInfoContributor implements InfoContributor {

    private final ChunkIndexAdmin admin;
    private final RagProperties props;
    private final PromptRenderer renderer;
    private final Environment env;

    public IndexInfoContributor(ChunkIndexAdmin admin, RagProperties props, PromptRenderer renderer, Environment env) {
        this.admin = admin;
        this.props = props;
        this.renderer = renderer;
        this.env = env;
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("rag", rag());
    }

    /** The version map served by {@code /v1/info} and embedded in actuator's info endpoint. */
    public Map<String, String> rag() {
        Map<String, String> rag = new LinkedHashMap<>();
        admin.aliasInfo(props.retrieval().chunksTarget(props.envPrefix())).ifPresent(info -> {
            rag.putAll(info.meta());
            rag.put("index", info.index());
        });
        // Generation-side settings a report needs to be reproducible (spec 03 §9).
        rag.put("generationModel", env.getProperty("spring.ai.deepseek.chat.options.model", ""));
        rag.put("answerPrompt", renderer.templateVersion());
        rag.put("minSimilarity", String.valueOf(props.ask().minSimilarity()));
        return rag;
    }
}
