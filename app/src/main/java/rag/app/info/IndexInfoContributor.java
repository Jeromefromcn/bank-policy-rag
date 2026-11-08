package rag.app.info;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;
import rag.app.RagProperties;
import rag.app.es.ChunkIndexAdmin;
import rag.app.es.IndexNames;

/** Exposes the live index's versions so eval reports can record them (spec 01 §4). */
@Component
public class IndexInfoContributor implements InfoContributor {

    private final ChunkIndexAdmin admin;
    private final RagProperties props;

    public IndexInfoContributor(ChunkIndexAdmin admin, RagProperties props) {
        this.admin = admin;
        this.props = props;
    }

    @Override
    public void contribute(Info.Builder builder) {
        Map<String, String> rag = new LinkedHashMap<>();
        admin.aliasInfo(IndexNames.chunksAlias(props.envPrefix())).ifPresent(info -> {
            rag.putAll(info.meta());
            rag.put("index", info.index());
        });
        builder.withDetail("rag", rag);
    }
}
