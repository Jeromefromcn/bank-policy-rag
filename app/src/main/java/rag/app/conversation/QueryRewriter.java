package rag.app.conversation;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import rag.app.ask.GenerationUnavailableException;
import rag.app.ask.PromptRenderer;
import rag.core.chunk.TokenCounter;

/**
 * Rewrites a follow-up into one standalone search query with {@code prompts/rewrite.v1.st} (spec 04 §6.2). History is
 * fenced as data like the sources of {@code answer.v2}. A blank, multi-line or over-long reply falls back to the
 * original follow-up; a failed model call is a {@link GenerationUnavailableException}.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class QueryRewriter {

    static final String VERSION = "rewrite.v1";
    static final int ANSWER_TOKENS = 300;
    static final int MAX_QUERY_CHARS = 512;
    static final int MAX_TOKENS = 128;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(history|question)}");

    /**
     * @param fellBack true when the model reply was unusable and {@code query} is the original follow-up
     */
    public record Rewrite(String query, boolean fellBack, Integer promptTokens, Integer completionTokens, long latencyMs) {}

    private final ChatModel chat;
    private final TokenCounter tokens;
    private final String system;
    private final String user;

    @Autowired
    public QueryRewriter(ChatModel chat) {
        this(chat, TokenCounter.cl100k());
    }

    QueryRewriter(ChatModel chat, TokenCounter tokens) {
        this.chat = chat;
        this.tokens = tokens;
        String template = PromptRenderer.load("prompts/" + VERSION + ".st");
        int u = template.indexOf("[user]\n");
        if (!template.startsWith("[system]\n") || u < 0) {
            throw new IllegalStateException(VERSION + ": expected [system] and [user] sections");
        }
        this.system = template.substring("[system]\n".length(), u).strip();
        this.user = template.substring(u + "[user]\n".length()).strip();
    }

    public Rewrite rewrite(List<Turn> history, String followUp) {
        long t0 = System.nanoTime();
        ChatResponse response;
        try {
            response = chat.call(render(history, followUp));
        } catch (RuntimeException e) {
            throw new GenerationUnavailableException("rewrite failed: " + e.getMessage(), e);
        }
        long latencyMs = (System.nanoTime() - t0) / 1_000_000;
        var usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        Integer promptTokens = usage == null ? null : usage.getPromptTokens();
        Integer completionTokens = usage == null ? null : usage.getCompletionTokens();
        String text = response.getResult() == null ? null : response.getResult().getOutput().getText();
        String query = text == null ? "" : text.strip();
        boolean usable = !query.isEmpty() && query.indexOf('\n') < 0 && query.indexOf('\r') < 0
                && query.length() <= MAX_QUERY_CHARS;
        return usable
                ? new Rewrite(query, false, promptTokens, completionTokens, latencyMs)
                : new Rewrite(followUp, true, promptTokens, completionTokens, latencyMs);
    }

    Prompt render(List<Turn> history, String followUp) {
        StringBuilder turns = new StringBuilder();
        for (int i = 0; i < history.size(); i++) {
            Turn t = history.get(i);
            String answer = t.answer() == null ? "(no answer)" : tokens.truncate(t.answer(), ANSWER_TOKENS);
            if (i > 0) {
                turns.append("\n\n");
            }
            turns.append("<<<TURN ").append(i + 1).append(">>>\nUser: ").append(PromptRenderer.neutralise(t.question()))
                    .append("\nAssistant: ").append(PromptRenderer.neutralise(answer)).append("\n<<<END TURN ")
                    .append(i + 1).append(">>>");
        }
        Map<String, String> values = Map.of("history", turns.toString(), "question", followUp);
        Matcher m = PLACEHOLDER.matcher(user);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(values.get(m.group(1))));
        }
        m.appendTail(out);
        // Start from the model's own defaults so the provider keeps its options type (DeepSeekChatModel casts them).
        ChatOptions defaults = chat.getDefaultOptions();
        ChatOptions options = (defaults == null ? ChatOptions.builder() : defaults.mutate())
                .temperature(0.0).maxTokens(MAX_TOKENS).build();
        return new Prompt(List.of(new SystemMessage(system), new UserMessage(out.toString())), options);
    }
}
