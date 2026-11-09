package rag.app.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import rag.app.RagProperties;
import rag.app.security.Principal;
import rag.core.model.Clearance;

class ConversationStoreTest {

    private static final Principal ALICE = new Principal("alice", Clearance.INTERNAL, Set.of());

    private final MutableClock clock = new MutableClock();

    private ConversationStore store(int maxConversations) {
        return new ConversationStore(new RagProperties.Conversation(Duration.ofMinutes(30), 5, maxConversations), clock);
    }

    private static Turn turn(int n) {
        return new Turn("q" + n, "rq" + n, "ANSWERED", "a" + n);
    }

    @Test
    void createdIsEmptyHistory() {
        ConversationStore s = store(1000);
        String id = s.create(ALICE);
        assertThat(id).startsWith("cv_");
        assertThat(s.history(id, ALICE)).contains(List.of());
    }

    @Test
    void appendThenHistoryInOrder() {
        ConversationStore s = store(1000);
        String id = s.create(ALICE);
        s.append(id, ALICE, turn(1));
        s.append(id, ALICE, turn(2));
        assertThat(s.history(id, ALICE)).contains(List.of(turn(1), turn(2)));
    }

    @Test
    void keepsOnlyLastFiveTurns() {
        ConversationStore s = store(1000);
        String id = s.create(ALICE);
        for (int i = 1; i <= 7; i++) {
            s.append(id, ALICE, turn(i));
        }
        assertThat(s.history(id, ALICE)).contains(List.of(turn(3), turn(4), turn(5), turn(6), turn(7)));
    }

    @Test
    void unknownIdIsNotFound() {
        assertThat(store(1000).history("cv_nope", ALICE)).isEmpty();
    }

    @Test
    void expiredIsNotFound() {
        ConversationStore s = store(1000);
        String id = s.create(ALICE);
        clock.advance(Duration.ofMinutes(29));
        assertThat(s.history(id, ALICE)).isPresent(); // a read refreshes idle time
        clock.advance(Duration.ofMinutes(29));
        assertThat(s.history(id, ALICE)).isPresent();
        clock.advance(Duration.ofMinutes(31));
        assertThat(s.history(id, ALICE)).isEmpty();
    }

    @Test
    void appendToExpiredIsNoOp() {
        ConversationStore s = store(1000);
        String id = s.create(ALICE);
        clock.advance(Duration.ofMinutes(31));
        s.append(id, ALICE, turn(1));
        assertThat(s.history(id, ALICE)).isEmpty();
    }

    @Test
    void foreignPrincipalIsNotFound() {
        ConversationStore s = store(1000);
        String id = s.create(ALICE);
        Principal bob = new Principal("bob", Clearance.INTERNAL, Set.of());
        assertThat(s.history(id, bob)).isEmpty();
        s.append(id, bob, turn(1));
        assertThat(s.history(id, ALICE)).contains(List.of());
    }

    @Test
    void changedClearanceIsNotFound() {
        ConversationStore s = store(1000);
        String id = s.create(ALICE);
        Principal lowered = new Principal("alice", Clearance.PUBLIC, Set.of());
        assertThat(s.history(id, lowered)).isEmpty();
    }

    @Test
    void evictsOldestBeyondMaxConversations() {
        ConversationStore s = store(2);
        String first = s.create(ALICE);
        String second = s.create(ALICE);
        String third = s.create(ALICE);
        assertThat(s.history(first, ALICE)).isEmpty();
        assertThat(s.history(second, ALICE)).isPresent();
        assertThat(s.history(third, ALICE)).isPresent();
    }

    @Test
    void concurrentAppendsKeepBothTurns() throws Exception {
        ConversationStore s = store(1000);
        String id = s.create(ALICE);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 1; i <= 2; i++) {
                Turn t = turn(i);
                pool.submit(() -> {
                    start.await();
                    s.append(id, ALICE, t);
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(s.history(id, ALICE).orElseThrow()).containsExactlyInAnyOrder(turn(1), turn(2));
    }

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-10T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
