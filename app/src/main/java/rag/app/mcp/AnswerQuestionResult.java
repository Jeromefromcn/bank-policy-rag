package rag.app.mcp;

import java.util.List;
import rag.app.ask.AskService;

/**
 * Lean {@code answer_question} payload (spec 06 §3.3): the REST citation shape plus the {@code sectionId} taken from
 * the retrieved hit, so an agent can expand any citation with {@code get_section}.
 */
public record AnswerQuestionResult(String answerId, String status, String refusalReason, String answer,
        List<Citation> citations) {

    public record Citation(int ref, String chunkId, String sectionId, String docId, List<String> sectionPath,
            List<Integer> pages, String quote) {}

    public static AnswerQuestionResult from(AskService.AskResult result) {
        var response = result.response();
        var citations = response.citations().stream().map(c -> new Citation(c.ref(), c.chunkId(),
                result.hits().get(c.ref() - 1).sectionId(), c.docId(), c.sectionPath(), c.pages(), c.quote())).toList();
        return new AnswerQuestionResult(response.answerId(), response.status(), response.refusalReason(),
                response.answer(), citations);
    }
}
