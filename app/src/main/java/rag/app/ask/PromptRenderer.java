package rag.app.ask;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import rag.app.search.SearchHit;

/**
 * Renders {@code prompts/answer.v3.st}: fenced, numbered sources (spec 03 §4). Templates are never edited in place; a change is a new
 * version (spec 00 §5). Placeholders are substituted in one pass over the template only, so
 * retrieved text or a question containing {@code {question}} is never expanded.
 */
@Component
public class PromptRenderer {

    static final String VERSION = "answer.v3";
    /** The reply the model gives when the sources do not contain the answer (spec 03 §4). */
    public static final String NO_ANSWER = "NO_ANSWER";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(sources|question)}");

    /** Three or more angle brackets, whatever the case, spacing, line breaks or full-width form around them. */
    private static final Pattern FENCE_LIKE = Pattern.compile("[<\\uFF1C](?:\\s*[<\\uFF1C]){2,}");

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

    /**
     * True when the model declined: the reply is {@code NO_ANSWER} possibly quoted, in backticks or followed by
     * punctuation or an explanation, but carries no citation (a cited reply is an answer and goes to validation).
     */
    public static boolean isNoAnswer(String reply) {
        String text = reply.strip().replaceAll("^[`\"'*\\s]+", "");
        return text.startsWith(NO_ANSWER) && !text.matches("(?s).*\\[\\s*\\d.*");
    }

    public String templateVersion() {
        return VERSION;
    }

    /** The two messages plus a canonical text form for the audit record (spec 05 §4.2). */
    public record Rendered(String system, String user) {
        public String text() {
            return "[system]\n" + system + "\n[user]\n" + user;
        }

        public Prompt toPrompt() {
            return new Prompt(List.of(new SystemMessage(system), new UserMessage(user)));
        }
    }

    public Prompt render(String question, List<SearchHit> hits) {
        return renderDetailed(question, hits).toPrompt();
    }

    public Rendered renderDetailed(String question, List<SearchHit> hits) {
        StringBuilder sources = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            SearchHit h = hits.get(i);
            String section = h.sectionPath().isEmpty() ? "-" : h.sectionPath().getLast();
            if (i > 0) {
                sources.append("\n\n");
            }
            sources.append("<<<SOURCE ").append(i + 1).append(" docId=").append(neutralise(h.docId())).append(" section=")
                    .append(neutralise(section)).append(">>>\n").append(neutralise(h.text())).append("\n<<<END SOURCE ")
                    .append(i + 1).append(">>>");
        }
        Map<String, String> values = Map.of("sources", sources.toString(), "question", question);
        Matcher m = PLACEHOLDER.matcher(user);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(values.get(m.group(1))));
        }
        m.appendTail(out);
        return new Rendered(system, out.toString());
    }

    /** Rebuilds the two messages from the audit record's canonical prompt text. */
    public static Prompt parse(String text) {
        return parse(text, null);
    }

    /** As {@link #parse(String)}, with the recorded options so replay reproduces them too. */
    public static Prompt parse(String text, ChatOptions options) {
        String prefix = "[system]\n";
        String marker = "\n[user]\n";
        int u = text == null ? -1 : text.indexOf(marker);
        if (text == null || !text.startsWith(prefix) || u < 0) {
            throw new IllegalArgumentException("malformed rendered prompt");
        }
        List<Message> messages = List.of(new SystemMessage(text.substring(prefix.length(), u)),
                new UserMessage(text.substring(u + marker.length())));
        return options == null ? new Prompt(messages) : new Prompt(messages, options);
    }

    /** A source must not be able to close or open a fence: any run of three or more '<' is replaced by U+2039 marks. */
    public static String neutralise(String text) {
        return FENCE_LIKE.matcher(text).replaceAll("\u2039\u2039\u2039");
    }

    /** Reads a prompt template from the classpath with LF line endings. */
    public static String load(String resource) {
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
