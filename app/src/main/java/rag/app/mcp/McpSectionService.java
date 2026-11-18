package rag.app.mcp;

import com.github.f4b6a3.ulid.UlidCreator;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;
import rag.app.RagProperties;
import rag.app.audit.AuditRecord;
import rag.app.audit.AuditService;
import rag.app.es.ChunkIndexAdmin;
import rag.app.sections.SectionLookup;
import rag.app.sections.SectionResponse;
import rag.app.security.Principal;

/**
 * {@code get_section} lookup plus its audit record (spec 06 §4.3). REST section lookup stays unaudited; the MCP tool
 * writes one record for every completed call, found or not, and writes it before returning the section, so an audit
 * failure cannot return content. The record carries the section id, never the section text.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class McpSectionService {

    private final SectionLookup lookup;
    private final AuditService audit;
    private final ChunkIndexAdmin indexAdmin;
    private final RagProperties props;

    public McpSectionService(SectionLookup lookup, AuditService audit, ChunkIndexAdmin indexAdmin, RagProperties props) {
        this.lookup = lookup;
        this.audit = audit;
        this.indexAdmin = indexAdmin;
        this.props = props;
    }

    public Optional<SectionResponse> find(String sectionId, Principal principal) {
        long t0 = System.nanoTime();
        var found = lookup.find(sectionId, principal);
        var meta = indexAdmin.aliasInfo(props.retrieval().chunksTarget(props.envPrefix()))
                .map(ChunkIndexAdmin.AliasInfo::meta).orElse(Map.of());
        var record = new AuditRecord(null, "s_" + UlidCreator.getMonotonicUlid(), Instant.now().toString(),
                audit.traceId(), principal.name(), principal.clearance().wire(), "mcp:get_section",
                sectionId, null, null, null, null, null, null, java.util.List.of(),
                found.isPresent() ? "OK" : "NOT_FOUND", null, null, null,
                new AuditRecord.Latency(0, 0, 0, 0, ms(t0)),
                meta.get("corpusVersion"), meta.get("chunkerVersion"), meta.get("embeddingModel"));
        audit.write(record);
        return found;
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
