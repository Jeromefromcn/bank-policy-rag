package rag.app.search;

import com.github.f4b6a3.ulid.UlidCreator;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import rag.app.security.Principal;

@RestController
public class SearchController {

    private final VectorRetriever retriever;

    public SearchController(VectorRetriever retriever) {
        this.retriever = retriever;
    }

    @PostMapping("/v1/search")
    SearchResponse search(@RequestBody SearchRequest request, @AuthenticationPrincipal Principal principal) {
        int topK = request.validatedTopK();
        return new SearchResponse("q_" + UlidCreator.getMonotonicUlid(), retriever.search(request.query(), topK, principal));
    }
}
