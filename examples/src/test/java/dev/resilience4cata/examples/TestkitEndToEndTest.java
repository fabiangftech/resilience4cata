package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.CircuitBreakerHandle;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Generated code on a registry that follows a {@link ManualClock}: delays of seconds, tests of milliseconds. */
class TestkitEndToEndTest {

    private final ManualClock clock = new ManualClock();
    private final ResilienceRegistry registry = ResilienceTesting.registry(clock).build();
    private final Stub stub = new Stub(clock);
    private final TestkitClient client = Resilient.of(TestkitClient.class, stub, registry);

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void retriesWithOneSecondBackoffFinishAtOnce() {
        stub.failures = 3;
        long started = System.nanoTime();

        assertThat(client.fetch("a")).isEqualTo("fetched a");

        assertThat(stub.callsAt).containsExactly(0L, seconds(1), seconds(2), seconds(3));
        assertThat(clock.elapsed()).isEqualTo(Duration.ofSeconds(3));
        assertThat(System.nanoTime() - started).isLessThan(seconds(1));
    }

    @Test
    void theBreakerLetsAProbeThroughOnceTheClockPassesWaitInOpen() {
        stub.failures = 2;
        assertThatThrownBy(() -> client.stock("x")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client.stock("x")).isInstanceOf(IllegalStateException.class);
        CircuitBreakerHandle breaker = registry.circuitBreaker("inventory");
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.OPEN);

        clock.advance(Duration.ofSeconds(29));
        assertThatThrownBy(() -> client.stock("x")).isInstanceOf(CallNotPermittedException.class);

        ResilienceTesting.elapseWaitInOpen(breaker, clock); // or clock.advance(Duration.ofSeconds(30))
        assertThat(client.stock("x")).isEqualTo("stock x"); // the half-open probe succeeds
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.CLOSED);
    }

    @Test
    void seededJitterMakesDelaysReproducible() {
        List<Long> first = jitteredAttempts(42);
        assertThat(jitteredAttempts(42)).isEqualTo(first);
        assertThat(jitteredAttempts(43)).isNotEqualTo(first);
    }

    private static List<Long> jitteredAttempts(long seed) {
        ManualClock own = new ManualClock();
        Stub failing = new Stub(own);
        failing.failures = 4;
        TestkitClient jittered = Resilient.of(TestkitClient.class, failing,
                ResilienceTesting.registry(own, seed).build());
        assertThat(jittered.jittered("j")).isEqualTo("fetched j");
        return failing.callsAt;
    }

    @Test
    void aSlowCallSimulatedOnTheClockTimesOut() {
        stub.slowness = Duration.ofSeconds(5);

        assertThatThrownBy(() -> client.slow("s")).isInstanceOf(CallTimeoutException.class);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();

        stub.slowness = Duration.ofMillis(1_500);
        assertThat(client.slow("s")).isEqualTo("slow s");
    }

    @Test
    void asyncRetriesAndTimeoutsFollowTheClock() {
        stub.failures = 2;
        CompletableFuture<String> fetched = client.fetchAsync("a");
        assertThat(fetched).isNotDone();
        clock.advance(Duration.ofSeconds(1));
        assertThat(fetched).isNotDone();
        clock.advance(Duration.ofSeconds(1));
        assertThat(fetched).isCompletedWithValue("fetched a");

        CompletableFuture<String> never = new CompletableFuture<>();
        stub.pending = () -> never;
        CompletableFuture<String> slow = client.slowAsync("s");
        clock.advance(Duration.ofSeconds(2));
        assertThatThrownBy(slow::join).isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(CallTimeoutException.class);
        assertThat(never).isCancelled();
    }

    private static long seconds(long seconds) {
        return Duration.ofSeconds(seconds).toNanos();
    }

    private static final class Stub implements TestkitClient {
        private final ManualClock clock;
        final List<Long> callsAt = new CopyOnWriteArrayList<>();
        volatile int failures;
        volatile Duration slowness = Duration.ZERO;
        volatile Supplier<CompletableFuture<String>> pending = CompletableFuture::new;

        Stub(ManualClock clock) {
            this.clock = clock;
        }

        private void maybeFail() {
            callsAt.add(clock.read());
            if (callsAt.size() <= failures) {
                throw new IllegalStateException("down");
            }
        }

        @Override
        public String fetch(String key) {
            maybeFail();
            return "fetched " + key;
        }

        @Override
        public String jittered(String key) {
            return fetch(key);
        }

        @Override
        public String stock(String sku) {
            maybeFail();
            return "stock " + sku;
        }

        @Override
        public String slow(String key) {
            try {
                clock.sleep(slowness); // a slow dependency, on the test's clock
            } catch (InterruptedException e) {
                throw new IllegalStateException("interrupted", e);
            }
            return "slow " + key;
        }

        @Override
        public CompletableFuture<String> fetchAsync(String key) {
            try {
                return CompletableFuture.completedFuture(fetch(key));
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        @Override
        public CompletableFuture<String> slowAsync(String key) {
            return pending.get();
        }
    }
}
