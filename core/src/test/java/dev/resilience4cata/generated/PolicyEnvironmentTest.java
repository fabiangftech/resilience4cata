package dev.resilience4cata.generated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.RetryConfig;
import dev.resilience4cata.Ticker;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.Test;

class PolicyEnvironmentTest {

    @Test
    void withoutTickerOrRandomIsTheProductionEnvironment() {
        assertThat(PolicyEnvironment.of(null, null)).isSameAs(PolicyEnvironment.DEFAULT);
        PolicyEnvironment env = PolicyEnvironment.DEFAULT;
        assertThat(env.manual).isNull();
        assertThat(env.random).isNull();
        assertThat(env.watchdog()).isSameAs(Watchdog.shared());
        assertThat(env.timeoutTimer()).isSameAs(TimeoutTimer.shared());
    }

    @Test
    void aRandomAloneKeepsTheSystemClockAndSharedTimers() {
        Random random = new Random(1);
        PolicyEnvironment env = PolicyEnvironment.of(null, random);
        assertThat(env.random).isSameAs(random);
        assertThat(env.ticker).isSameAs(PolicyEnvironment.DEFAULT.ticker);
        assertThat(env.sleeper).isSameAs(PolicyEnvironment.DEFAULT.sleeper);
        assertThat(env.watchdog()).isSameAs(Watchdog.shared());
    }

    @Test
    void aTickerGetsItsOwnZeroSlackWatchdogDrivenByItsAdvances() {
        FakeTicker ticker = new FakeTicker();
        PolicyEnvironment env = PolicyEnvironment.of(ticker, null);

        assertThat(env.timeoutTimer()).isNull();
        Watchdog dog = env.watchdog();
        assertThat(dog).isNotSameAs(Watchdog.shared());
        dog.requireTimeout(1_000_000_000L);
        assertThat(dog.tick()).isZero();
        ticker.moveTo(123);
        assertThat(dog.now()).isEqualTo(123);
        assertThat(env.ticker.getAsLong()).isEqualTo(123);
    }

    @Test
    void delaysGoToTheTickerButImmediateTasksDoNot() {
        FakeTicker ticker = new FakeTicker();
        PolicyEnvironment env = PolicyEnvironment.of(ticker, null);
        CompletableFuture<Void> immediate = new CompletableFuture<>();

        env.later(0, () -> immediate.complete(null));
        env.later(10, () -> { });

        immediate.join();
        assertThat(ticker.scheduled).containsExactly(10L);
    }

    @Test
    void seededJitterIsReproducible() {
        RetryConfig config = RetryConfig.builder().maxAttempts(10).jitter(RetryConfig.Jitter.FULL).build();
        RetryPolicy first = new RetryPolicy("r", config, PolicyEnvironment.of(null, new Random(5)));
        RetryPolicy second = new RetryPolicy("r", config, PolicyEnvironment.of(null, new Random(5)));
        for (int attempt = 1; attempt < 10; attempt++) {
            assertThat(first.onFailure(attempt, new IllegalStateException(), 0))
                    .isEqualTo(second.onFailure(attempt, new IllegalStateException(), 0));
        }
    }

    @Test
    void builderRejectsNulls() {
        assertThatNullPointerException().isThrownBy(() -> ResilienceRegistry.builder().ticker(null));
        assertThatNullPointerException().isThrownBy(() -> ResilienceRegistry.builder().random(null));
    }

    private static final class FakeTicker implements Ticker {
        private final List<LongConsumer> listeners = new ArrayList<>();
        final List<Long> scheduled = new ArrayList<>();
        private volatile long now;

        void moveTo(long time) {
            now = time;
            listeners.forEach(listener -> listener.accept(time));
        }

        @Override
        public long read() {
            return now;
        }

        @Override
        public void sleep(long nanos) {
            moveTo(now + nanos);
        }

        @Override
        public Future<?> schedule(long delayNanos, Runnable task) {
            scheduled.add(delayNanos);
            return new CompletableFuture<>();
        }

        @Override
        public void onAdvance(LongConsumer listener) {
            listeners.add(listener);
        }
    }
}
