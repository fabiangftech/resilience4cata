package dev.resilience4cata.generated;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.RetryConfig;
import dev.resilience4cata.RetrySnapshot;
import dev.resilience4cata.event.EventListener;
import dev.resilience4cata.event.ResilienceEvent;
import dev.resilience4cata.event.RetryEvent;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The retry budget as {@link RetryPolicy} uses it: counted by {@code start()}, asked last by {@code decide}. */
class RetryPolicyBudgetTest {

    private final AtomicLong now = new AtomicLong(1_000);
    private final List<RetryEvent.GaveUp> gaveUp = new ArrayList<>();

    @SuppressWarnings("unchecked")
    private RetryPolicy policy(RetryConfig.Builder builder) {
        EventDispatcher events = EventDispatcher.of(List.of(new EventDispatcher.Subscription(RetryEvent.GaveUp.class, null,
                (EventListener<ResilienceEvent>) e -> gaveUp.add((RetryEvent.GaveUp) e))));
        return new RetryPolicy("budgeted", builder.build(), PolicyEnvironment.forTest(now::get, now::addAndGet, events));
    }

    /**
     * Reserve of 3 retries in the window. The percent turns the budget on and is too small to add a retry
     * for the few calls of these tests (allowed = 3 + 0.00001 × calls, and a retry needs a whole one).
     */
    private static RetryConfig.Builder threeRetries() {
        return RetryConfig.builder().jitter(RetryConfig.Jitter.NONE).delay(Duration.ZERO).maxAttempts(10)
                .budgetPercent(0.001).budgetMinRetriesPerSecond(1).budgetWindow(Duration.ofSeconds(3));
    }

    @Test
    void withoutABudgetStartReadsNoClockAndThereIsNothingToSnapshot() {
        RetryPolicy retry = policy(RetryConfig.builder());
        AtomicInteger reads = new AtomicInteger();
        RetryPolicy counted = new RetryPolicy(RetryConfig.DEFAULTS, () -> reads.incrementAndGet(), nanos -> { });
        assertThat(reads).as("no clock read to create the policy").hasValue(0);

        assertThat(counted.start()).isZero();
        assertThat(reads).hasValue(0);
        assertThat(retry.budgetSnapshot()).isNull();
        assertThat(RetryConfig.DEFAULTS.hasBudget()).isFalse();
    }

    @Test
    void aDisabledRetryHasNoBudget() {
        assertThat(policy(threeRetries().enabled(false)).budgetSnapshot()).isNull();
    }

    @Test
    void retriesStopWithBudgetExhaustedOnceTheBudgetIsUsed() {
        RetryPolicy retry = policy(threeRetries());
        IOException failure = new IOException("down");

        long start = retry.start();
        assertThat(retry.onFailure(1, failure, start)).isZero();
        assertThat(retry.onFailure(2, failure, start)).isZero();
        assertThat(retry.onFailure(3, failure, start)).isZero();
        assertThat(retry.onFailure(4, failure, start)).isEqualTo(RetryPolicy.NO_RETRY);

        assertThat(gaveUp).singleElement().satisfies(e -> {
            assertThat(e.reason()).isEqualTo(RetryEvent.Reason.BUDGET_EXHAUSTED);
            assertThat(e.attempts()).isEqualTo(4);
            assertThat(e.failure()).isSameAs(failure);
            assertThat(e.instance()).isEqualTo("budgeted");
        });
        assertThat(retry.budgetSnapshot()).isEqualTo(new RetrySnapshot.Budget(1, 3, 3));
    }

    @Test
    void theBudgetIsSharedByEveryCallOfTheInstanceAndRefillsWhenTheWindowMoves() {
        RetryPolicy retry = policy(threeRetries());
        IOException failure = new IOException("down");
        for (int call = 0; call < 3; call++) {
            assertThat(retry.onFailure(1, failure, retry.start())).isZero();
        }
        assertThat(retry.onFailure(1, failure, retry.start())).isEqualTo(RetryPolicy.NO_RETRY);

        now.addAndGet(Duration.ofSeconds(4).toNanos());
        assertThat(retry.onFailure(1, failure, retry.start())).isZero();
    }

    @Test
    void thePercentCountsCallsNotAttempts() {
        RetryPolicy retry = policy(RetryConfig.builder().jitter(RetryConfig.Jitter.NONE).delay(Duration.ZERO)
                .maxAttempts(10).budgetPercent(50).budgetMinRetriesPerSecond(0)); // the percentage alone
        IOException failure = new IOException("down");
        for (int call = 0; call < 4; call++) {
            retry.start();
        }
        assertThat(retry.onFailure(1, failure, 0)).isZero();
        assertThat(retry.onFailure(2, failure, 0)).isZero();
        assertThat(retry.onFailure(3, failure, 0)).as("2 retries for 4 calls").isEqualTo(RetryPolicy.NO_RETRY);
    }

    @Test
    void aRetryThatOtherRulesRefuseTakesNothingFromTheBudget() {
        RetryPolicy retry = policy(threeRetries().maxAttempts(2).abortOn(List.of(IllegalArgumentException.class)));
        IOException failure = new IOException("down");

        assertThat(retry.onFailure(2, failure, 0)).isEqualTo(RetryPolicy.NO_RETRY);
        assertThat(retry.onFailure(1, new IllegalArgumentException(), 0)).isEqualTo(RetryPolicy.NO_RETRY);
        assertThat(retry.onFailure(1, failure, 0, Classifier.Outcome.SUCCESS)).isEqualTo(RetryPolicy.NO_RETRY);

        assertThat(gaveUp).extracting(RetryEvent.GaveUp::reason)
                .containsExactly(RetryEvent.Reason.MAX_ATTEMPTS, RetryEvent.Reason.NOT_RETRYABLE, RetryEvent.Reason.NOT_RETRYABLE);
        assertThat(retry.budgetSnapshot()).isEqualTo(new RetrySnapshot.Budget(0, 0, 3));
    }

    @Test
    void rejectedResultsAndClassifiedFailuresTakeFromTheSameBudget() {
        RetryPolicy retry = policy(threeRetries());

        assertThat(retry.onFailedResult(1, 0)).isZero();
        assertThat(retry.onFailure(1, new IOException(), 0, Classifier.Outcome.FAILURE)).isZero();
        assertThat(retry.onFailedResult(2, 0)).isZero();
        assertThat(retry.onFailedResult(3, 0)).isEqualTo(RetryPolicy.NO_RETRY);
        assertThat(gaveUp).singleElement().satisfies(e -> {
            assertThat(e.reason()).isEqualTo(RetryEvent.Reason.BUDGET_EXHAUSTED);
            assertThat(e.failure()).isNull();
        });
    }

    @Test
    void asyncRetriesTakeFromTheBudgetToo() {
        RetryPolicy retry = new RetryPolicy("async", threeRetries().budgetMinRetriesPerSecond(0).budgetPercent(100)
                .build(), PolicyEnvironment.forTest(now::get, now::addAndGet));
        AtomicInteger attempts = new AtomicInteger();

        // the first call counts once, so it may be retried once
        CompletableFuture<String> result = retry.executeAsync(() -> {
            attempts.incrementAndGet();
            return CompletableFuture.failedFuture(new IOException("down"));
        });

        assertThat(result).failsWithin(Duration.ofSeconds(5));
        assertThat(attempts).hasValue(2);
        assertThat(retry.budgetSnapshot()).isEqualTo(new RetrySnapshot.Budget(1, 1, 1));
    }
}
