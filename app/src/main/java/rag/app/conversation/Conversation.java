package rag.app.conversation;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import rag.core.model.Clearance;

/** Mutable state of one conversation; only {@link ConversationStore} touches it, under its lock. */
final class Conversation {

    final String principalName;
    final Clearance clearance;
    final Deque<Turn> turns = new ArrayDeque<>();
    Instant lastAccess;

    Conversation(String principalName, Clearance clearance, Instant now) {
        this.principalName = principalName;
        this.clearance = clearance;
        this.lastAccess = now;
    }
}
