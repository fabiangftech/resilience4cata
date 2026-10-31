package dev.resilience4cata.testkit;

import dev.resilience4cata.generated.Policies;
import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.generated.BulkheadPolicy;
import dev.resilience4cata.generated.CircuitBreakerPolicy;
import dev.resilience4cata.generated.HedgePolicy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** A hedge on a {@link ManualClock}: delays are exact, and hedges start inline on the thread that advances the clock. */
class HedgeOnManualClockTest {

    private final ManualClock clock = new ManualClock();
    private final ResilienceRegistry registry = ResilienceTesting.registry(clock)
            .hedgeDefaults(h -> h.delay(Duration.ofMillis(50)).budgetPercent(0))
            .build();
    private final List<CompletableFuture<String>> attempts = new CopyOnWriteArrayList<>();
    private final List<Thread> threads = new CopyOnWriteArrayList<>();

    private CompletableFuture<String> attempt() {
        threads.add(Thread.currentThread());
        CompletableFuture<String> stage = new CompletableFuture<>();
        attempts.add(stage);
        return stage;
    }

    @Test
    void theHedgeStartsExactlyAtTheDelayOnTheAdvancingThread() {
        HedgePolicy hedge = Policies.hedge(registry, "hedge", "", "", null, false);
        CompletableFuture<String> result = hedge.executeAsync(this::attempt);
        assertThat(clock.pendingTasks()).isOne();

        clock.advance(Duration.ofMillis(49));
        assertThat(attempts).hasSize(1);
        clock.advance(Duration.ofMillis(1));
        assertThat(attempts).hasSize(2);
        assertThat(threads).as("inline, deterministic").containsOnly(Thread.currentThread());

        attempts.get(1).complete("hedge");
        assertThat(result).isCompletedWithValue("hedge");
        assertThat(attempts.get(0)).isCancelled();
        assertThat(clock.pendingTasks()).isZero();
    }

    @Test
    void aCallThatFinishesInTimeLeavesNoPendingTask() {
        HedgePolicy hedge = Policies.hedge(registry, "hedge", "", "", null, false);
        CompletableFuture<String> result = hedge.executeAsync(this::attempt);
        attempts.get(0).complete("first");
        assertThat(result).isCompletedWithValue("first");
        assertThat(clock.pendingTasks()).as("the delay is taken off the clock").isZero();
        assertThat(clock.advanceUntilIdle()).isEqualTo(Duration.ZERO);
    }

    @Test
    void eachAttemptTakesItsOwnPermitsAndTheLoserGivesThemBack() {
        HedgePolicy hedge = Policies.hedge(registry, "quotes", "", "", h -> h.maxAttempts(3), false);
        CircuitBreakerPolicy breaker = Policies.circuitBreaker(registry, "quotes", "", "", cb -> cb.minimumCalls(1), false);
        BulkheadPolicy bulkhead = Policies.bulkhead(registry, "quotes", "", "", b -> b.maxConcurrent(3), false);
        CompletableFuture<String> result = hedge.executeAsync(
                () -> breaker.executeAsync(() -> bulkhead.executeAsync(this::attempt)));

        clock.advance(Duration.ofMillis(100));
        assertThat(attempts).hasSize(3);
        assertThat(bulkhead.concurrentCalls()).isEqualTo(3);

        attempts.get(1).complete("second");

        assertThat(result).isCompletedWithValue("second");
        assertThat(bulkhead.concurrentCalls()).as("every slot is free").isZero();
        assertThat(breaker.snapshot().bufferedCalls()).as("only the winner is recorded").isOne();
        assertThat(breaker.snapshot().failedCalls()).isZero();
        assertThat(registry.snapshot().hedges()).extracting(s -> s.instance()).containsExactly("quotes");
    }
}
