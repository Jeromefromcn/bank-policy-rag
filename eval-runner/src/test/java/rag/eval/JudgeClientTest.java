package rag.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rag.eval.judge.JudgeCache;
import rag.eval.judge.JudgeClient;
import rag.eval.judge.JudgeVerdict;
import tools.jackson.databind.json.JsonMapper;

class JudgeClientTest {

    @TempDir Path tmp;

    JudgeClient client(StubServer stub, String model) {
        return new JudgeClient(stub.base(), "judge-key", model, new JudgeCache(tmp.resolve("cache")));
    }

    @Test
    void parsesPerPointVerdictsAndSendsModelAndAuth() throws Exception {
        try (var stub = new StubServer()) {
            stub.judgeResponder = body -> StubServer.judgeContent("{\"verdicts\":[\"yes\",\"No\"]}");

            var verdicts = client(stub, "judge-model").judgePoints("Answer [1].", List.of("source text"), List.of("p1", "p2"));

            assertThat(verdicts).containsExactly(JudgeVerdict.SUPPORTED, JudgeVerdict.UNSUPPORTED);
            var body = JsonMapper.builder().build().readTree(stub.requests.getFirst().body());
            assertThat(body.get("model").asString()).isEqualTo("judge-model");
            assertThat(body.get("temperature").asDouble()).isZero();
            assertThat(body.get("response_format").get("type").asString()).isEqualTo("json_object");
        }
    }

    @Test
    void malformedJsonIsErrorNotSupported() throws Exception {
        try (var stub = new StubServer()) {
            stub.judgeResponder = body -> StubServer.judgeContent("this is not json");

            var verdicts = client(stub, "m").judgePoints("A [1].", List.of("s"), List.of("p1", "p2"));

            assertThat(verdicts).containsExactly(JudgeVerdict.ERROR, JudgeVerdict.ERROR);
            assertThat(stub.judgeCalls()).isEqualTo(2);
        }
    }

    @Test
    void wrongLengthArrayIsError() throws Exception {
        try (var stub = new StubServer()) {
            stub.judgeResponder = body -> StubServer.judgeContent("{\"verdicts\":[\"yes\"]}");

            assertThat(client(stub, "m").judgePoints("A [1].", List.of("s"), List.of("p1", "p2")))
                    .containsExactly(JudgeVerdict.ERROR, JudgeVerdict.ERROR);
        }
    }

    @Test
    void unknownVerdictWordIsError() throws Exception {
        try (var stub = new StubServer()) {
            stub.judgeResponder = body -> StubServer.judgeContent("{\"verdicts\":[\"maybe\"]}");

            assertThat(client(stub, "m").judgePoints("A [1].", List.of("s"), List.of("p1"))).containsExactly(JudgeVerdict.ERROR);
        }
    }

    @Test
    void httpFailureIsErrorNotException() throws Exception {
        try (var stub = new StubServer()) {
            stub.judgeStatus = 500;

            assertThat(client(stub, "m").judgePoints("A [1].", List.of("s"), List.of("p1"))).containsExactly(JudgeVerdict.ERROR);
        }
    }

    @Test
    void parsesSentenceVerdicts() throws Exception {
        try (var stub = new StubServer()) {
            stub.judgeResponder = body -> StubServer.judgeContent("{\"verdicts\":[\"no\",\"yes\"]}");

            var verdicts = client(stub, "m").judgeSentences(List.of(
                    new JudgeClient.SentenceWithSources("S1.", List.of("a")), new JudgeClient.SentenceWithSources("S2.", List.of("b"))));

            assertThat(verdicts).containsExactly(JudgeVerdict.UNSUPPORTED, JudgeVerdict.SUPPORTED);
        }
    }

    @Test
    void emptyInputMakesNoCall() throws Exception {
        try (var stub = new StubServer()) {
            assertThat(client(stub, "m").judgePoints("A [1].", List.of("s"), List.of())).isEmpty();
            assertThat(stub.judgeCalls()).isZero();
        }
    }

    @Test
    void cacheHitSkipsNetwork() throws Exception {
        try (var stub = new StubServer()) {
            var c = client(stub, "m");
            c.judgePoints("A [1].", List.of("s"), List.of("p1"));
            c.judgePoints("A [1].", List.of("s"), List.of("p1"));

            assertThat(stub.judgeCalls()).isEqualTo(1);
        }
    }

    @Test
    void errorsAreNotCached() throws Exception {
        try (var stub = new StubServer()) {
            stub.judgeResponder = body -> StubServer.judgeContent("garbage");
            var c = client(stub, "m");
            c.judgePoints("A [1].", List.of("s"), List.of("p1"));
            stub.judgeResponder = body -> StubServer.judgeContent("{\"verdicts\":[\"yes\"]}");

            assertThat(c.judgePoints("A [1].", List.of("s"), List.of("p1"))).containsExactly(JudgeVerdict.SUPPORTED);
        }
    }

    @Test
    void changedModelMisses() throws Exception {
        try (var stub = new StubServer()) {
            client(stub, "m1").judgePoints("A [1].", List.of("s"), List.of("p1"));
            client(stub, "m2").judgePoints("A [1].", List.of("s"), List.of("p1"));

            assertThat(stub.judgeCalls()).isEqualTo(2);
        }
    }

    @Test
    void cacheKeyChangesWithPromptVersionModelAndInput() {
        String base = JudgeCache.key("judge-point.v1", "m", "in");
        assertThat(JudgeCache.key("judge-point.v2", "m", "in")).isNotEqualTo(base);
        assertThat(JudgeCache.key("judge-point.v1", "m2", "in")).isNotEqualTo(base);
        assertThat(JudgeCache.key("judge-point.v1", "m", "in2")).isNotEqualTo(base);
        assertThat(JudgeCache.key("judge-point.v1", "m", "in")).isEqualTo(base);
    }

    @Test
    void differentJudgeUrlMisses() throws Exception {
        try (var a = new StubServer(); var b = new StubServer()) {
            new JudgeClient(a.base(), "k", "m", new JudgeCache(tmp.resolve("shared"))).judgePoints("A [1].", List.of("s"), List.of("p1"));
            new JudgeClient(b.base(), "k", "m", new JudgeCache(tmp.resolve("shared"))).judgePoints("A [1].", List.of("s"), List.of("p1"));

            assertThat(a.judgeCalls()).isEqualTo(1);
            assertThat(b.judgeCalls()).isEqualTo(1);
        }
    }

    @Test
    void retrySucceedsOnTheSecondAttempt() throws Exception {
        try (var stub = new StubServer()) {
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            stub.judgeResponder = body -> calls.incrementAndGet() == 1 ? StubServer.judgeContent("garbage")
                    : StubServer.judgeContent("{\"verdicts\":[\"yes\"]}");
            var client = new JudgeClient(stub.base(), "k", "m", new JudgeCache(tmp.resolve("c")), java.time.Duration.ZERO);

            assertThat(client.judgePoints("A [1].", List.of("s"), List.of("p1"))).containsExactly(JudgeVerdict.SUPPORTED);
            assertThat(calls.get()).isEqualTo(2);
        }
    }

    @Test
    void retryWaitsBetweenAttempts() throws Exception {
        try (var stub = new StubServer()) {
            stub.judgeResponder = body -> StubServer.judgeContent("garbage");
            var client = new JudgeClient(stub.base(), "k", "m", new JudgeCache(tmp.resolve("c")), java.time.Duration.ofMillis(300));

            long t0 = System.nanoTime();
            client.judgePoints("A [1].", List.of("s"), List.of("p1"));

            assertThat((System.nanoTime() - t0) / 1_000_000).isGreaterThanOrEqualTo(300);
        }
    }

    @Test
    void judgeUrlWithABasePathKeepsThePath() throws Exception {
        try (var stub = new StubServer()) {
            var client = new JudgeClient(java.net.URI.create(stub.base() + "/v1"), "k", "m", new JudgeCache(tmp.resolve("c")));

            assertThat(client.judgePoints("A [1].", List.of("s"), List.of("p1"))).containsExactly(JudgeVerdict.SUPPORTED);
            assertThat(stub.requests.getFirst().path()).isEqualTo("/v1/chat/completions");
        }
    }
}
