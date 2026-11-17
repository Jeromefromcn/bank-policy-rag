package rag.app.info;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticated version map for the eval runner (spec 05 §5.3): actuator moved to the management port,
 * so the runner reads the same data from the API port instead of {@code /actuator/info}.
 */
@RestController
public class InfoController {

    private final IndexInfoContributor info;

    public InfoController(IndexInfoContributor info) {
        this.info = info;
    }

    @GetMapping("/v1/info")
    Map<String, String> info() {
        return info.rag();
    }
}
