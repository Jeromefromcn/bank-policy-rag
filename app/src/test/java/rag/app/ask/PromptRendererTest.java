package rag.app.ask;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.prompt.Prompt;
import rag.app.search.SearchHit;

class PromptRendererTest {

    private final PromptRenderer renderer = new PromptRenderer();

    static SearchHit hit(String docId, List<String> sectionPath, String text) {
        return new SearchHit("c", "s", docId, "T", sectionPath, List.of(), List.of(), "public", 1, Map.of(), text);
    }

    static String text(Prompt p, MessageType type) {
        return p.getInstructions().stream().filter(m -> m.getMessageType() == type).findFirst().orElseThrow().getText();
    }

    @Test
    void numbersSourcesFromOne() {
        var p = renderer.render("Q?", List.of(hit("eb-a", List.of("2"), "alpha"), hit("spm-or-2", List.of("3", "3.2"), "beta")));
        String user = text(p, MessageType.USER);
        assertThat(user).contains("[1] docId=eb-a section=2\nalpha").contains("[2] docId=spm-or-2 section=3.2\nbeta").contains("Q?");
        assertThat(user.indexOf("[1]")).isLessThan(user.indexOf("[2]"));
    }

    @Test
    void systemMessageStatesTheRules() {
        String system = text(renderer.render("Q?", List.of(hit("d", List.of(), "x"))), MessageType.SYSTEM);
        assertThat(system).contains("Answer only from the numbered sources").contains("[1]");
    }

    @Test
    void sourceWithoutSectionShowsDash() {
        assertThat(text(renderer.render("Q?", List.of(hit("d", List.of(), "x"))), MessageType.USER)).contains("[1] docId=d section=-");
    }

    @Test
    void placeholdersInContentAreNotExpanded() {
        String user = text(renderer.render("{sources}", List.of(hit("d", List.of("1"), "{question}"))), MessageType.USER);
        assertThat(user).contains("section=1\n{question}").contains("Question: {sources}");
    }

    @Test
    void templateVersion() {
        assertThat(renderer.templateVersion()).isEqualTo("answer.v1");
    }
}
