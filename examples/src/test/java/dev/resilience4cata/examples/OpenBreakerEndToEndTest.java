package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.Pipeline;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.RetryConfig;
import dev.resilience4cata.event.RetryEvent;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * A call rejected by an open circuit breaker is never retried, even when {@code abortOn} is set and does
 * not list {@code CallNotPermittedException}: generated sync, async and classified code, and pipelines.
 */
class OpenBreakerEndToEndTest {

    private final List<RetryEvent.GaveUp> gaveUp = new CopyOnWriteArrayList<>();
    private final ResilienceRegistry registry = ResilienceRegistry.builder()
            .listen(RetryEvent.GaveUp.class, gaveUp::add)
            .build();
    private final AtomicInteger calls = new AtomicInteger();

    private final OpenBreakerClient client = Resilient.of(OpenBreakerClient.class, new OpenBreakerClient() {
        @Override
        public String sync(String id) {
            calls.incrementAndGet();
            throw new IllegalStateException("down");
        }

        @Override
        public CompletableFuture<String> async(String id) {
            calls.incrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException("down"));
        }

        @Override
        public String classified(String id) {
            return sync(id);
        }
    }, registry);

    /** Two failures open the breaker; the third attempt is rejected and is the last one. */
    private void assertStoppedAtTheRejection() {
        assertThat(calls).hasValue(2);
        assertThat(gaveUp).extracting(RetryEvent.GaveUp::attempts, RetryEvent.GaveUp::reason)
                .containsExactly(tuple(3, RetryEvent.Reason.NOT_RETRYABLE));
        assertThat(gaveUp.getFirst().failure()).isInstanceOf(CallNotPermittedException.class);
    }

    @Test
    void syncRetriesStopAtAnOpenBreaker() {
        assertThatThrownBy(() -> client.sync("a")).isInstanceOf(CallNotPermittedException.class);
        assertStoppedAtTheRejection();
    }

    @Test
    void asyncRetriesStopAtAnOpenBreaker() {
        assertThat(client.async("a")).failsWithin(5, TimeUnit.SECONDS).withThrowableThat()
                .havingCause().isInstanceOf(CallNotPermittedException.class);
        assertStoppedAtTheRejection();
    }

    @Test
    void aClassifierThatFailsEveryExceptionStillStopsAtAnOpenBreaker() {
        assertThatThrownBy(() -> client.classified("a")).isInstanceOf(CallNotPermittedException.class);
        assertStoppedAtTheRejection();
    }

    @Test
    void pipelineRetriesStopAtAnOpenBreaker() {
        Pipeline<String> pipeline = Pipeline.builder(registry, "open-breaker", String.class)
                .retry(r -> r.maxAttempts(10).delay(Duration.ZERO).jitter(RetryConfig.Jitter.NONE)
                        .abortOn(List.of(IllegalArgumentException.class)))
                .circuitBreaker(cb -> cb.windowSize(2).minimumCalls(2).failureRateThreshold(50)
                        .waitInOpen(Duration.ofHours(1)))
                .build();

        assertThatThrownBy(() -> pipeline.execute(() -> {
            calls.incrementAndGet();
            throw new IllegalStateException("down");
        })).isInstanceOf(CallNotPermittedException.class);
        assertStoppedAtTheRejection();
    }
}
