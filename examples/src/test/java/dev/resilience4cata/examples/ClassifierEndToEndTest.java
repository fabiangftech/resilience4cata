package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.examples.ClassifiedClient.Reply;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

class ClassifierEndToEndTest {

    private final ResilienceRegistry registry = ResilienceRegistry.builder().build();

    private final AtomicInteger calls = new AtomicInteger();
    /** What the n-th call (from 1) does: returns a Reply or throws a Throwable. */
    private volatile IntFunction<Object> behaviour = n -> new Reply(200, "ok");

    private final ClassifiedClient client = Resilient.of(ClassifiedClient.class, new ClassifiedClient() {
        @Override
        public Reply get(String path) throws IOException {
            return call();
        }

        @Override
        public Reply probe(String path) throws IOException {
            return call();
        }

        @Override
        public Reply withFallback(String path) throws IOException {
            return call();
        }

        @Override
        public Reply broken(String path) throws IOException {
            return call();
        }

        @Override
        public CompletionStage<Reply> getAsync(String path) {
            try {
                return CompletableFuture.completedFuture(call());
            } catch (Throwable e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        @Override
        public void send(String path) throws IOException {
            call();
        }

        private Reply call() throws IOException {
            Object outcome = behaviour.apply(calls.incrementAndGet());
            if (outcome instanceof IOException e) {
                throw e;
            }
            if (outcome instanceof RuntimeException e) {
                throw e;
            }
            return (Reply) outcome;
        }
    }, registry);

    @Test
    void retriesFailureResultsUntilOneIsAccepted() throws IOException {
        behaviour = n -> new Reply(n < 3 ? 503 : 200, "reply " + n);

        assertThat(client.get("/a")).isEqualTo(new Reply(200, "reply 3"));
        assertThat(calls).hasValue(3);
    }

    @Test
    void returnsTheLastResultWhenRetriesAreExhausted() throws IOException {
        behaviour = n -> new Reply(429, "reply " + n);

        assertThat(client.get("/a")).isEqualTo(new Reply(429, "reply 3"));
        assertThat(calls).hasValue(3);
    }

    @Test
    void classifiesExceptionsToo() {
        behaviour = n -> new IOException("io " + n);
        assertThatThrownBy(() -> client.get("/a")).isInstanceOf(IOException.class).hasMessage("io 3");
        assertThat(calls).hasValue(3);

        calls.set(0);
        behaviour = n -> new IllegalArgumentException("bad request");
        assertThatIllegalArgumentException().isThrownBy(() -> client.get("/a")); // IGNORE: not retried
        assertThat(calls).hasValue(1);
    }

    @Test
    void theBreakerRecordsFailureResultsAndIgnoresWhatTheClassifierIgnores() throws IOException {
        behaviour = n -> new IllegalArgumentException("ignored");
        for (int i = 0; i < 5; i++) {
            assertThatIllegalArgumentException().isThrownBy(() -> client.probe("/a"));
        }
        assertThat(registry.circuitBreaker("classified-strict").state()).isEqualTo(CircuitBreakerState.CLOSED);

        behaviour = n -> new Reply(404, "not found"); // a success for this classifier
        assertThat(client.probe("/a").status()).isEqualTo(404);
        behaviour = n -> new Reply(500, "down");
        assertThat(client.probe("/a").status()).isEqualTo(500);
        assertThat(registry.circuitBreaker("classified-strict").state()).isEqualTo(CircuitBreakerState.OPEN);
        assertThatThrownBy(() -> client.probe("/a")).isInstanceOf(CallNotPermittedException.class);
    }

    @Test
    void aFinalFailureResultDoesNotRunTheFallbackButAnExceptionDoes() throws IOException {
        behaviour = n -> new Reply(503, "reply " + n);
        assertThat(client.withFallback("/a")).isEqualTo(new Reply(503, "reply 2"));

        behaviour = n -> new IOException("io " + n);
        assertThat(client.withFallback("/a")).isEqualTo(new Reply(200, "cached /a: io 4"));
    }

    @Test
    void aClassifierThatThrowsFailsTheCallThroughTheFallbackAndCountsAsABreakerFailure() throws IOException {
        assertThat(client.broken("/a")).isEqualTo(new Reply(200, "cached /a: classifier bug"));
        assertThat(calls).hasValue(1); // no retry
        assertThat(client.broken("/a")).isEqualTo(new Reply(200, "cached /a: classifier bug"));
        assertThat(registry.circuitBreaker("classified-broken").state()).isEqualTo(CircuitBreakerState.OPEN);
    }

    @Test
    void asyncMethodsRetryRecordAndFallBackTheSameWay() throws Exception {
        behaviour = n -> new Reply(n < 2 ? 503 : 200, "reply " + n);
        assertThat(client.getAsync("/a").toCompletableFuture().get(5, TimeUnit.SECONDS)).isEqualTo(new Reply(200, "reply 2"));

        calls.set(0);
        behaviour = n -> new Reply(503, "reply " + n);
        assertThat(client.getAsync("/a").toCompletableFuture().get(5, TimeUnit.SECONDS)).isEqualTo(new Reply(503, "reply 3"));

        // 4 failure results and 1 success recorded so far: this attempt opens the breaker, the next is rejected,
        // the retry stops on the rejection and the fallback answers.
        Reply fallback = client.getAsync("/a").toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(fallback.body()).startsWith("cached /a: Circuit breaker 'classified-async'");
        assertThat(calls).hasValue(4);

        calls.set(0);
        registry.circuitBreaker("classified-async").reset();
        behaviour = n -> new IOException("io " + n);
        assertThat(client.getAsync("/a").toCompletableFuture().get(5, TimeUnit.SECONDS))
                .isEqualTo(new Reply(200, "cached /a: io 3"));
    }

    @Test
    void voidMethodsAreClassifiedWithANullResult() throws IOException {
        client.send("/a");
        assertThat(calls).hasValue(1);

        behaviour = n -> new IOException("io " + n);
        assertThatThrownBy(() -> client.send("/a")).isInstanceOf(IOException.class).hasMessage("io 4");
    }
}
