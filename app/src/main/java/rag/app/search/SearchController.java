package rag.app.search;

import com.github.f4b6a3.ulid.UlidCreator;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import rag.app.security.Principal;

@RestController
public class SearchController {

    private final HybridSearch search;

    public SearchController(HybridSearch search) {
        this.search = search;
    }

    @PostMapping("/v1/search")
    SearchResponse search(@RequestBody SearchRequest request, @AuthenticationPrincipal Principal principal) {
        var options = request.validated();
        return new SearchResponse("q_" + UlidCreator.getMonotonicUlid(),
                search.search(request.query(), options.topK(), options.mode(), principal));
    }
}
