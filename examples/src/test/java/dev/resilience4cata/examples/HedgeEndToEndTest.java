package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.CircuitBreakerSnapshot;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.event.HedgeEvent;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** {@code @Hedge} through generated code, on a {@link ManualClock} and on the real timer,. */
class HedgeEndToEndTest {

    private final ManualClock clock = new ManualClock();
    private final List<HedgeEvent> events = new CopyOnWriteArrayList<>();
    private final ResilienceRegistry registry = ResilienceTesting.registry(clock)
            .listen(HedgeEvent.class, events::add)
            .build();
    private final Replicas replicas = new Replicas();
    private final HedgeClient client = Resilient.of(HedgeClient.class, replicas, registry);

    /** Each call is an attempt whose stage the test completes. */
    static final class Replicas implements HedgeClient {
        final List<CompletableFuture<String>> attempts = new CopyOnWriteArrayList<>();

        @Override
        public CompletableFuture<String> price(String sku) {
            CompletableFuture<String> attempt = new CompletableFuture<>();
            attempts.add(attempt);
            return attempt;
        }

        @Override
        public CompletionStage<String> read(String key) {
            return price(key);
        }
    }

    @Test
    void theFirstAttemptWinsWithoutAHedge() {
        CompletableFuture<String> price = client.price("sku");
        replicas.attempts.get(0).complete("12.50");
        assertThat(price).isCompletedWithValue("12.50");
        assertThat(clock.pendingTasks()).isZero();
        assertThat(events).isEmpty();
    }

    @Test
    void aHedgeWinsAgainstASlowReplicaAndTheLoserIsNotRecorded() {
        CompletableFuture<String> price = client.price("sku");
        clock.advance(Duration.ofMillis(50));
        assertThat(replicas.attempts).hasSize(2);

        replicas.attempts.get(1).complete("12.50");

        assertThat(price).isCompletedWithValue("12.50");
        assertThat(replicas.attempts.get(0)).isCancelled();
        CircuitBreakerSnapshot breaker = registry.circuitBreaker("replicas").snapshot();
        assertThat(breaker.bufferedCalls()).isOne();
        assertThat(breaker.failedCalls()).isZero();
        assertThat(events).containsExactly(
                new HedgeEvent.Hedged(HedgeClient.class.getName() + "#price(java.lang.String)", 50_000_000L, 2,
                        HedgeEvent.Reason.DELAY),
                new HedgeEvent.Won(HedgeClient.class.getName() + "#price(java.lang.String)", 50_000_000L, 2));
        assertThat(clock.pendingTasks()).as("timeouts and delays are off the clock").isZero();
    }

    @Test
    void perAttemptTimeoutsStartHedgesAndTheFallbackGetsTheAttempts() {
        CompletableFuture<String> price = client.price("sku");
        clock.advance(Duration.ofMillis(50));
        clock.advance(Duration.ofMillis(50));
        assertThat(replicas.attempts).hasSize(3);
        clock.advanceUntilIdle();
        assertThat(price).isCompletedWithValue("cached after 3: CallTimeoutException");
        assertThat(replicas.attempts).allSatisfy(attempt -> assertThat(attempt).isCancelled());
    }

    @Test
    void failuresStartHedgesAtOnce() {
        CompletableFuture<String> price = client.price("sku");
        replicas.attempts.get(0).completeExceptionally(new IOException("replica down"));
        clock.advance(Duration.ZERO); // not on the completing thread: on the thread that advances the clock
        assertThat(replicas.attempts).as("no wait for the delay").hasSize(2);
        replicas.attempts.get(1).completeExceptionally(new IOException("replica down"));
        clock.advance(Duration.ZERO);
        replicas.attempts.get(2).completeExceptionally(new IOException("replica down"));
        clock.advance(Duration.ZERO);
        assertThat(price).isCompletedWithValue("cached after 3: IOException");
    }

    @Test
    void cancellingTheCallCancelsEveryAttempt() {
        CompletableFuture<String> price = client.price("sku");
        clock.advance(Duration.ofMillis(50));
        price.cancel(true);
        assertThat(replicas.attempts).hasSize(2).allSatisfy(attempt -> assertThat(attempt).isCancelled());
        assertThat(clock.pendingTasks()).isZero();
    }

    @Test
    void onTheRealTimerASlowFirstAttemptIsHedged() throws Exception {
        ResilienceRegistry real = ResilienceRegistry.builder().build();
        HedgeClient service = Resilient.of(HedgeClient.class, new HedgeClient() {
            private int calls;

            @Override
            public CompletableFuture<String> price(String sku) {
                throw new UnsupportedOperationException();
            }

            @Override
            public synchronized CompletionStage<String> read(String key) {
                return ++calls == 1 ? new CompletableFuture<>() : CompletableFuture.completedFuture("value of " + key);
            }
        }, real);

        assertThat(service.read("k").toCompletableFuture().get(5, TimeUnit.SECONDS)).isEqualTo("value of k");
        assertThat(real.snapshot().hedges()).extracting(s -> s.id()).contains("reads");
    }
}
