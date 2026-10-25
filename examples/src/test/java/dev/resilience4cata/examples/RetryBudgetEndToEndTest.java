package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import dev.resilience4cata.Pipeline;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.RetrySnapshot;
import dev.resilience4cata.event.RetryEvent;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** A retry budget against a dependency that is down: generated sync and async code, a fallback, and {@link Pipeline}. */
class RetryBudgetEndToEndTest {

    private final ManualClock clock = new ManualClock();
    private final List<RetryEvent.GaveUp> gaveUp = new CopyOnWriteArrayList<>();
    private final ResilienceRegistry registry = ResilienceTesting.registry(clock)
            .listen(RetryEvent.GaveUp.class, gaveUp::add)
            .build();
    private final AtomicInteger attempts = new AtomicInteger();

    /** Always fails. */
    private final BudgetedClient down = new BudgetedClient() {
        @Override
        public String get(String id) {
            attempts.incrementAndGet();
            throw new IllegalStateException("backend down");
        }

        @Override
        public CompletableFuture<String> getAsync(String id) {
            attempts.incrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException("backend down"));
        }

        @Override
        public String getOrCached(String id) {
            return get(id);
        }
    };

    private long reasons(RetryEvent.Reason reason) {
        return gaveUp.stream().filter(e -> e.reason() == reason).count();
    }

    private RetrySnapshot.Budget budget(String instance) {
        return registry.snapshot().retries().stream().filter(r -> r.instance().equals(instance)).findFirst()
                .orElseThrow().budget();
    }

    @Test
    void totalAttemptsStayWithinTheBudget() {
        BudgetedClient client = Resilient.of(BudgetedClient.class, down, registry);

        for (int call = 0; call < 50; call++) {
            assertThatIllegalStateException().isThrownBy(() -> client.get("a"));
        }

        // 5 s × 1 retry per second + 10% of 50 calls = 10 retries, instead of 100 without a budget
        assertThat(attempts).hasValue(50 + 10);
        assertThat(budget("backend")).isEqualTo(new RetrySnapshot.Budget(50, 10, 10));
        assertThat(reasons(RetryEvent.Reason.BUDGET_EXHAUSTED)).isEqualTo(50 - 2);
        assertThat(reasons(RetryEvent.Reason.MAX_ATTEMPTS)).isEqualTo(2);
        assertThat(gaveUp).filteredOn(e -> e.reason() == RetryEvent.Reason.BUDGET_EXHAUSTED)
                .allSatisfy(e -> assertThat(e.instance()).isEqualTo("backend"));
    }

    @Test
    void theBudgetIsSharedBySyncAsyncAndFallbackCallsAndRefillsOnceTheWindowHasPassed() {
        BudgetedClient client = Resilient.of(BudgetedClient.class, down, registry);
        for (int call = 0; call < 3; call++) {
            assertThatIllegalStateException().isThrownBy(() -> client.get("a"));
        }
        assertThat(attempts).as("2 + 2 + 1 retries: 5 of the 5.3 allowed").hasValue(3 + 5);

        attempts.set(0);
        assertThat(client.getAsync("b")).isCompletedExceptionally();
        assertThat(client.getOrCached("c")).isEqualTo("cached c");
        assertThat(attempts).as("one attempt each: the budget is used up").hasValue(2);
        assertThat(gaveUp.subList(gaveUp.size() - 2, gaveUp.size())).extracting(RetryEvent.GaveUp::reason)
                .containsOnly(RetryEvent.Reason.BUDGET_EXHAUSTED);

        clock.advance(Duration.ofSeconds(6));
        attempts.set(0);
        CompletableFuture<String> async = client.getAsync("d");
        clock.advance(Duration.ofMillis(10));
        clock.advance(Duration.ofMillis(20));
        assertThat(async).isCompletedExceptionally();
        assertThat(attempts).as("a new window: retried again").hasValue(3);
        assertThat(gaveUp.getLast().reason()).isEqualTo(RetryEvent.Reason.MAX_ATTEMPTS);
    }

    @Test
    void aPipelineRetryHasTheSameBudget() {
        Pipeline<String> pipeline = Pipeline.builder(registry, "pipeline-backend", String.class)
                .retry(r -> r.maxAttempts(3).delay(Duration.ZERO).budgetPercent(0.001).budgetMinRetriesPerSecond(1)
                        .budgetWindow(Duration.ofSeconds(5)))
                .build();

        for (int call = 0; call < 5; call++) {
            assertThatIllegalStateException().isThrownBy(() -> pipeline.execute(() -> down.get("a")));
        }
        assertThat(attempts).as("5 retries in the window, then none").hasValue(5 + 5);
        assertThat(pipeline.executeAsync(() -> down.getAsync("b"))).isCompletedExceptionally();
        assertThat(attempts).hasValue(11);
        assertThat(gaveUp.getLast().reason()).isEqualTo(RetryEvent.Reason.BUDGET_EXHAUSTED);
        assertThat(budget("pipeline-backend")).isEqualTo(new RetrySnapshot.Budget(6, 5, 5));
    }
}
