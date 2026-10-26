package dev.resilience4cata.examples.docs.hedge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.HedgeCancelledException;
import dev.resilience4cata.HedgeConfig;
import dev.resilience4cata.HedgeSnapshot;
import dev.resilience4cata.Pipeline;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.event.HedgeEvent;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** Every claim and snippet of {@code docs/en/policies/hedge.md}. */
class HedgePageTest {

    private final ManualClock clock = new ManualClock();
    private final List<HedgeEvent> events = new CopyOnWriteArrayList<>();
    private final ResilienceRegistry registry = ResilienceTesting.registry(clock)
            .listen(HedgeEvent.class, events::add)
            .build();
    /** Every stage the stub returned, one per attempt. */
    private final List<CompletableFuture<String>> attempts = new CopyOnWriteArrayList<>();
    private final QuoteApi backend = sku -> {
        CompletableFuture<String> attempt = new CompletableFuture<>();
        attempts.add(attempt);
        return attempt;
    };

    @Test
    void testingOnAManualClock() {
        // tag::hedge-testing[]
        QuoteApi quotes = Resilient.of(QuoteApi.class, backend, registry);   // backend: one pending stage per call

        CompletableFuture<String> quote = quotes.quote("sku-1");
        clock.advance(Duration.ofMillis(49));
        assertThat(attempts).hasSize(1);                  // no hedge before the delay
        clock.advance(Duration.ofMillis(1));
        assertThat(attempts).hasSize(2);                  // the hedge started, on this thread

        attempts.get(1).complete("quote from attempt 2");
        assertThat(quote).isCompletedWithValue("quote from attempt 2");
        assertThat(attempts.get(0)).isCancelled();        // the loser is cancelled
        // end::hedge-testing[]
        assertThat(attempts.get(0)).isCompletedExceptionally();
        // The exception the loser was completed with; join() wraps it in a new CancellationException on JDK 23+.
        assertThat(attempts.get(0).handle((value, failure) -> failure).join()).isInstanceOf(HedgeCancelledException.class);
        assertThat(events).containsExactly(
                new HedgeEvent.Hedged(QUOTE_ID, clock.read(), 2, HedgeEvent.Reason.DELAY),
                new HedgeEvent.Won(QUOTE_ID, clock.read(), 2));
        assertThat(clock.pendingTasks()).isZero();
    }

    private static final String QUOTE_ID = "dev.resilience4cata.examples.docs.hedge.QuoteApi#quote(java.lang.String)";

    @Test
    void aFirstAttemptThatWinsBeforeTheDelayPublishesNothing() {
        QuoteApi quotes = Resilient.of(QuoteApi.class, backend, registry);
        CompletableFuture<String> quote = quotes.quote("sku-1");
        attempts.getFirst().complete("fast");
        assertThat(quote).isCompletedWithValue("fast");
        clock.advance(Duration.ofSeconds(1));
        assertThat(attempts).hasSize(1);
        assertThat(events).isEmpty();
        assertThat(clock.pendingTasks()).isZero();
    }

    @Test
    void aFailureStartsTheNextAttemptAtOnce() {
        QuoteApi quotes = Resilient.of(QuoteApi.class, backend, registry);
        CompletableFuture<String> quote = quotes.quote("sku-1");
        attempts.getFirst().completeExceptionally(new IllegalStateException("replica down"));
        assertThat(attempts).hasSize(1);   // not on the thread that completed the failed attempt
        clock.advance(Duration.ZERO);      // on a ManualClock, the next advance starts it
        assertThat(attempts).hasSize(2);   // no wait for the delay
        attempts.get(1).complete("second");
        assertThat(quote).isCompletedWithValue("second");
        assertThat(events.getFirst()).isEqualTo(new HedgeEvent.Hedged(QUOTE_ID, clock.read(), 2, HedgeEvent.Reason.FAILURE));
    }

    @Test
    void whenEveryAttemptFailsTheLastFailureWins() {
        QuoteApi quotes = Resilient.of(QuoteApi.class, backend, registry);
        CompletableFuture<String> quote = quotes.quote("sku-1");
        clock.advance(Duration.ofMillis(50));
        attempts.get(0).completeExceptionally(new IllegalStateException("first"));
        IllegalStateException last = new IllegalStateException("last");
        attempts.get(1).completeExceptionally(last);
        assertThatThrownBy(quote::join).isInstanceOf(CompletionException.class).hasCause(last);
        assertThat(events.getLast()).isEqualTo(new HedgeEvent.Failed(QUOTE_ID, clock.read(), 2, last));
    }

    @Test
    void abortOnEndsTheCallAndTheFallbackGetsTheAttempts() {
        List<CompletableFuture<Long>> priceAttempts = new CopyOnWriteArrayList<>();
        PriceApi prices = Resilient.of(PriceApi.class, sku -> {
            CompletableFuture<Long> attempt = new CompletableFuture<>();
            priceAttempts.add(attempt);
            return attempt;
        }, registry);

        CompletableFuture<Long> price = prices.price("sku-1");
        clock.advance(Duration.ofMillis(30));
        assertThat(priceAttempts).hasSize(2);
        priceAttempts.get(1).completeExceptionally(new IllegalArgumentException("unknown sku"));
        assertThat(price).isCompletedWithValue(-1L);    // the fallback, once
        assertThat(priceAttempts.get(0)).isCancelled();  // abortOn cancels the others
        assertThat(events).contains(new HedgeEvent.Skipped("prices", clock.read(), 3, HedgeEvent.SkipReason.ABORTED));
    }

    @Test
    void anOpenBreakerStopsFurtherHedges() {
        PriceApi prices = Resilient.of(PriceApi.class, sku -> new CompletableFuture<>(), registry);
        registry.circuitBreaker("prices").forceOpen();
        CompletableFuture<Long> price = prices.price("sku-1");
        assertThat(price).isCompletedWithValue(-1L);   // rejected at once: nothing in flight, the fallback runs
        assertThat(events).contains(new HedgeEvent.Skipped("prices", clock.read(), 2, HedgeEvent.SkipReason.REJECTED));
        assertThat(events.getLast()).isInstanceOfSatisfying(HedgeEvent.Failed.class,
                f -> assertThat(f.failure()).isInstanceOf(CallNotPermittedException.class));
    }

    @Test
    void theBudgetCapsHedges() {
        QuoteApi quotes = Resilient.of(QuoteApi.class, backend, registry);
        for (int i = 0; i < 20; i++) {
            quotes.quote("sku-" + i);
            clock.advance(Duration.ofMillis(50));
        }
        assertThat(attempts).hasSize(20 + 10 + 2);   // 20 calls, 10 (1/s over 10 s) + 2 (10% of 20) hedges
        assertThat(events).filteredOn(HedgeEvent.Skipped.class::isInstance).hasSize(8);
        assertThat(registry.snapshot().hedges().getFirst().budget()).isEqualTo(new HedgeSnapshot.Budget(20, 12, 12));
    }

    @Test
    void defaults() {
        assertThat(HedgeConfig.DEFAULTS.maxAttempts()).isEqualTo(2);
        assertThat(HedgeConfig.DEFAULTS.delay()).isEqualTo(Duration.ofSeconds(1));
        assertThat(HedgeConfig.DEFAULTS.hedgeOn()).containsExactly(Exception.class);
        assertThat(HedgeConfig.DEFAULTS.abortOn()).isEmpty();
        assertThat(HedgeConfig.DEFAULTS.budgetPercent()).isEqualTo(10.0);
        assertThat(HedgeConfig.DEFAULTS.budgetMinHedgesPerSecond()).isEqualTo(1);
        assertThat(HedgeConfig.DEFAULTS.budgetWindow()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void pipeline() {
        // tag::hedge-pipeline[]
        Pipeline<String> quote = Pipeline.builder(registry, "quotes", String.class)
                .hedge(h -> h.delay(Duration.ofMillis(50)))
                .build();

        CompletableFuture<String> price = quote.executeAsync(() -> backend.quote("sku-1"));
        // end::hedge-pipeline[]
        clock.advance(Duration.ofMillis(50));
        attempts.get(1).complete("hedged");
        assertThat(price).isCompletedWithValue("hedged");
        assertThat(registry.snapshot().hedges()).extracting(HedgeSnapshot::instance).containsExactly("quotes");
        assertThatThrownBy(() -> quote.execute(() -> "sync")).isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("[R4C-RT-018]");
    }
}
