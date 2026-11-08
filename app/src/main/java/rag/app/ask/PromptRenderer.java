package rag.app.ask;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import rag.app.search.SearchHit;

/**
 * Renders {@code prompts/answer.v1.st}. Templates are never edited in place; a change is a new
 * version (spec 00 §5). Placeholders are substituted in one pass over the template only, so
 * retrieved text or a question containing {@code {question}} is never expanded.
 */
@Component
public class PromptRenderer {

    static final String VERSION = "answer.v1";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(sources|question)}");

    private final String system;
    private final String user;

    public PromptRenderer() {
        String template = load("prompts/" + VERSION + ".st");
        int u = template.indexOf("[user]\n");
        if (!template.startsWith("[system]\n") || u < 0) {
            throw new IllegalStateException(VERSION + ": expected [system] and [user] sections");
        }
        this.system = template.substring("[system]\n".length(), u).strip();
        this.user = template.substring(u + "[user]\n".length()).strip();
    }

    public String templateVersion() {
        return VERSION;
    }

    public Prompt render(String question, List<SearchHit> hits) {
        StringBuilder sources = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            SearchHit h = hits.get(i);
            String section = h.sectionPath().isEmpty() ? "-" : h.sectionPath().getLast();
            if (i > 0) {
                sources.append("\n\n");
            }
            sources.append('[').append(i + 1).append("] docId=").append(h.docId()).append(" section=").append(section)
                    .append('\n').append(h.text());
        }
        Map<String, String> values = Map.of("sources", sources.toString(), "question", question);
        Matcher m = PLACEHOLDER.matcher(user);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(values.get(m.group(1))));
        }
        m.appendTail(out);
        return new Prompt(List.of(new SystemMessage(system), new UserMessage(out.toString())));
    }

    private static String load(String resource) {
        try (InputStream in = PromptRenderer.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing prompt template " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
