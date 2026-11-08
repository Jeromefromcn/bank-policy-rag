package rag.app.support;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/** Records prompts and answers "Answer [1]." with usage 100/10; can be told to fail. */
public class StubChatModel implements ChatModel {

    private final List<Prompt> prompts = new ArrayList<>();
    private volatile boolean fail;

    @Override
    public synchronized ChatResponse call(Prompt prompt) {
        prompts.add(prompt);
        if (fail) {
            throw new IllegalStateException("stub LLM failure");
        }
        var metadata = ChatResponseMetadata.builder().usage(new DefaultUsage(100, 10)).build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage("Answer [1]."))), metadata);
    }

    public synchronized int calls() {
        return prompts.size();
    }

    public synchronized Prompt lastPrompt() {
        return prompts.getLast();
    }

    public synchronized void reset() {
        prompts.clear();
        fail = false;
    }

    public void failNext() {
        fail = true;
    }
}
