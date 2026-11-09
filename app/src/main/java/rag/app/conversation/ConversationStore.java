package rag.app.conversation;

import com.github.f4b6a3.ulid.UlidCreator;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import rag.app.RagProperties;
import rag.app.security.Principal;

/**
 * In-memory conversations, lost on restart (spec 04 §6.1). Each conversation is bound to the principal name and
 * clearance that created it: any other caller, an expired id and an unknown id all look the same (not found).
 *
 * <p>A small bounded map in access order behind one lock: simplicity over throughput.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ConversationStore {

    private final RagProperties.Conversation limits;
    private final Clock clock;
    private final Map<String, Conversation> conversations;

    @Autowired
    public ConversationStore(RagProperties props, Clock clock) {
        this(props.conversation(), clock);
    }

    ConversationStore(RagProperties.Conversation limits, Clock clock) {
        this.limits = limits;
        this.clock = clock;
        this.conversations = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Conversation> eldest) {
                return size() > limits.maxConversations();
            }
        };
    }

    /** Starts an empty conversation owned by {@code p} and returns its id ({@code cv_} + ULID). */
    public String create(Principal p) {
        String id = "cv_" + UlidCreator.getMonotonicUlid();
        synchronized (conversations) {
            conversations.put(id, new Conversation(p.name(), p.clearance(), clock.instant()));
        }
        return id;
    }

    /** The turns, oldest first, or empty for an unknown, expired or foreign id. A hit refreshes the idle time. */
    public Optional<List<Turn>> history(String id, Principal p) {
        synchronized (conversations) {
            return find(id, p).map(c -> List.copyOf(c.turns));
        }
    }

    /** Adds a turn, keeping the last {@code maxTurns}. Does nothing when {@link #history} would be empty. */
    public void append(String id, Principal p, Turn turn) {
        synchronized (conversations) {
            find(id, p).ifPresent(c -> {
                c.turns.addLast(turn);
                while (c.turns.size() > limits.maxTurns()) {
                    c.turns.removeFirst();
                }
            });
        }
    }

    private Optional<Conversation> find(String id, Principal p) {
        Conversation c = conversations.get(id);
        if (c == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        if (now.isAfter(c.lastAccess.plus(limits.ttl()))) {
            conversations.remove(id);
            return Optional.empty();
        }
        if (!c.principalName.equals(p.name()) || c.clearance != p.clearance()) {
            return Optional.empty();
        }
        c.lastAccess = now;
        return Optional.of(c);
    }
}
