package dev.resilience4cata.examples.docs.testing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.junit.ResilienceTest;
import dev.resilience4cata.testkit.ManualClock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** {@code docs/en/guides/testing.md}: the settings of {@code @ResilienceTest}. */
// tag::testing-junit-settings[]
@ResilienceTest(seed = 7, failOnPendingTasks = true)
class AsyncQuoteServiceTest {

    @Test
    void asyncRetriesRunWhenTheClockAdvances(ManualClock clock, ResilienceRegistry registry) {
        AtomicInteger calls = new AtomicInteger();
        AsyncQuoteService quotes = Resilient.of(AsyncQuoteService.class, sku -> calls.incrementAndGet() < 3
                ? CompletableFuture.failedFuture(new IllegalStateException("upstream down"))
                : CompletableFuture.completedFuture("quote for " + sku), registry);

        CompletableFuture<String> quote = quotes.quote("A-1"); // the second attempt waits on the clock
        assertThat(quote).isNotDone();

        clock.advanceUntilIdle(); // without this line the test fails: a retry is still pending on the clock
        assertThat(quote).isCompletedWithValue("quote for A-1");
    }
}
// end::testing-junit-settings[]
