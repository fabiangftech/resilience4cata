package dev.resilience4cata.examples.docs.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.BulkheadFullException;
import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.CircuitBreakerHandle;
import dev.resilience4cata.CircuitBreakerSnapshot;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.RateLimitExceededException;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.event.ResilienceEvent;
import dev.resilience4cata.event.RetryEvent;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The code of {@code docs/en/guides/testing.md}: every recipe runs on a {@link ManualClock}. */
class TestingGuideTest {

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    // tag::testing-first-test[]
    @Test
    void retriesThreeTimesWithoutWaiting() {
        ManualClock clock = new ManualClock();
        ResilienceRegistry registry = ResilienceTesting.registry(clock).build();
        AtomicInteger calls = new AtomicInteger();
        QuoteService quotes = Resilient.of(QuoteService.class, sku -> {
            if (calls.incrementAndGet() < 4) {
                throw new IllegalStateException("upstream down");
            }
            return "quote for " + sku;
        }, registry);

        assertThat(quotes.quote("A-1")).isEqualTo("quote for A-1");

        assertThat(calls).hasValue(4);
        assertThat(clock.elapsed()).isEqualTo(Duration.ofSeconds(3)); // three 1 s delays, no real waiting
    }
    // end::testing-first-test[]

    // tag::testing-retry-backoff[]
    @Test
    void exponentialBackoffIsCappedAtMaxDelay() {
        ManualClock clock = new ManualClock();
        List<Duration> calledAt = new CopyOnWriteArrayList<>();
        BackoffService service = Resilient.of(BackoffService.class, new FailingBackoffService(clock, calledAt),
                ResilienceTesting.registry(clock).build());

        assertThatThrownBy(() -> service.load("x")).isInstanceOf(IllegalStateException.class);

        assertThat(calledAt).containsExactly(Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(3),
                Duration.ofSeconds(6)); // delays of 1 s, 2 s, 3 s
    }
    // end::testing-retry-backoff[]

    // tag::testing-seeded-jitter[]
    @Test
    void theSameSeedGivesTheSameJitter() {
        assertThat(callTimesWithSeed(7)).isEqualTo(callTimesWithSeed(7));
        assertThat(callTimesWithSeed(7)).isNotEqualTo(callTimesWithSeed(8));
    }

    private static List<Duration> callTimesWithSeed(long seed) {
        ManualClock clock = new ManualClock();
        List<Duration> calledAt = new CopyOnWriteArrayList<>();
        BackoffService service = Resilient.of(BackoffService.class, new FailingBackoffService(clock, calledAt),
                ResilienceTesting.registry(clock, seed).build());
        assertThatThrownBy(() -> service.loadJittered("x")).isInstanceOf(IllegalStateException.class);
        return calledAt;
    }
    // end::testing-seeded-jitter[]

    /** Records when it is called, on the test's clock, and always fails. */
    // tag::testing-retry-stub[]
    static final class FailingBackoffService implements BackoffService {
        private final ManualClock clock;
        private final List<Duration> calledAt;

        FailingBackoffService(ManualClock clock, List<Duration> calledAt) {
            this.clock = clock;
            this.calledAt = calledAt;
        }

        @Override
        public String load(String id) {
            calledAt.add(clock.elapsed());
            throw new IllegalStateException("still down");
        }

        @Override
        public String loadJittered(String id) {
            return load(id);
        }
    }
    // end::testing-retry-stub[]

    // tag::testing-breaker[]
    @Test
    void theBreakerOpensThenLetsAProbeThrough() {
        ManualClock clock = new ManualClock();
        ResilienceRegistry registry = ResilienceTesting.registry(clock).build();
        AtomicBoolean down = new AtomicBoolean(true);
        InventoryService inventory = Resilient.of(InventoryService.class, sku -> {
            if (down.get()) {
                throw new IllegalStateException("inventory down");
            }
            return 7;
        }, registry);

        for (int i = 0; i < 4; i++) { // four failures out of four calls: 100% >= 50%
            assertThatThrownBy(() -> inventory.stock("X")).isInstanceOf(IllegalStateException.class);
        }
        CircuitBreakerHandle breaker = registry.circuitBreaker("inventory");
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.OPEN);
        assertThatThrownBy(() -> inventory.stock("X")).isInstanceOf(CallNotPermittedException.class);

        down.set(false);
        ResilienceTesting.elapseWaitInOpen(breaker, clock); // clock.advance(30 s)
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.OPEN); // half-open only when a call arrives
        assertThat(inventory.stock("X")).isEqualTo(7); // the probe succeeds
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.CLOSED);
    }
    // end::testing-breaker[]

    // tag::testing-rate-limiter[]
    @Test
    void theThirdCallInTheSameInstantIsRejected() {
        ManualClock clock = new ManualClock();
        ResilienceRegistry registry = ResilienceTesting.registry(clock).build();
        SearchService search = Resilient.of(SearchService.class, new EchoSearch(), registry);

        search.search("a");
        search.search("b"); // permits = 2: a burst of two passes at once
        assertThatThrownBy(() -> search.search("c")).isInstanceOf(RateLimitExceededException.class);

        clock.advance(Duration.ofMillis(500)); // one permit every period / permits = 500 ms
        assertThat(search.search("c")).isEqualTo("results for c");
    }

    @Test
    void aCallerWithMaxWaitSleepsOnTheClock() {
        ManualClock clock = new ManualClock();
        SearchService search = Resilient.of(SearchService.class, new EchoSearch(),
                ResilienceTesting.registry(clock).build());

        search.export("a");
        assertThat(search.export("b")).isEqualTo("export of b"); // waited for the next permit

        assertThat(clock.elapsed()).isEqualTo(Duration.ofSeconds(1));
    }
    // end::testing-rate-limiter[]

    static final class EchoSearch implements SearchService {
        @Override
        public String search(String query) {
            return "results for " + query;
        }

        @Override
        public String export(String query) {
            return "export of " + query;
        }
    }

    // tag::testing-timeout[]
    @Test
    void aSlowCallTimesOutOnTheClock() {
        ManualClock clock = new ManualClock();
        ReportService reports = Resilient.of(ReportService.class, id -> {
            clock.sleep(Duration.ofSeconds(5)); // a slow dependency, simulated on the test's clock
            return "report " + id;
        }, ResilienceTesting.registry(clock).build());

        assertThatThrownBy(() -> reports.render("r-1")).isInstanceOf(CallTimeoutException.class);
        assertThat(Thread.currentThread().isInterrupted()).isFalse(); // the interrupt is cleared
        assertThat(clock.elapsed()).isEqualTo(Duration.ofSeconds(5)); // the sleep still advanced in full
    }
    // end::testing-timeout[]

    // tag::testing-bulkhead-async[]
    @Test
    void aQueuedCallIsRejectedWhenTheClockPassesMaxWait() {
        ManualClock clock = new ManualClock();
        ResilienceRegistry registry = ResilienceTesting.registry(clock).build();
        CompletableFuture<String> slowRender = new CompletableFuture<>(); // completed by the test
        RenderService renderer = Resilient.of(RenderService.class, new RenderStub(slowRender), registry);

        CompletableFuture<String> first = renderer.render("slow"); // holds the only slot
        CompletableFuture<String> queued = renderer.render("next"); // waits up to 30 s, no thread blocked
        assertThat(registry.bulkhead("renderer").waitingCalls()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(30));

        assertThatThrownBy(queued::join).isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(BulkheadFullException.class);
        slowRender.complete("rendered slow");
        assertThat(first.join()).isEqualTo("rendered slow");
    }
    // end::testing-bulkhead-async[]

    @Test
    void aQueuedCallRunsWhenTheSlotIsReleased() {
        ManualClock clock = new ManualClock();
        ResilienceRegistry registry = ResilienceTesting.registry(clock).build();
        CompletableFuture<String> slowRender = new CompletableFuture<>();
        RenderService renderer = Resilient.of(RenderService.class, new RenderStub(slowRender), registry);

        CompletableFuture<String> first = renderer.render("slow");
        CompletableFuture<String> queued = renderer.render("next");
        clock.advance(Duration.ofSeconds(10));
        slowRender.complete("rendered slow");

        assertThat(first.join()).isEqualTo("rendered slow");
        assertThat(queued.join()).isEqualTo("rendered next");
    }

    // tag::testing-async-retry[]
    @Test
    void asyncRetriesRunWhenTheClockAdvances() {
        ManualClock clock = new ManualClock();
        RenderStub stub = new RenderStub(new CompletableFuture<>());
        stub.failures.set(2);
        RenderService renderer = Resilient.of(RenderService.class, stub, ResilienceTesting.registry(clock).build());

        CompletableFuture<String> result = renderer.fetch("a"); // first attempt fails, retry scheduled in 1 s
        assertThat(result).isNotDone();

        clock.advance(Duration.ofSeconds(1)); // second attempt runs here, on this thread, and fails
        assertThat(result).isNotDone();
        assertThat(clock.advanceUntilIdle()).isEqualTo(Duration.ofSeconds(1)); // runs the third attempt

        assertThat(result).isCompletedWithValue("fetched a");
    }
    // end::testing-async-retry[]

    static final class RenderStub implements RenderService {
        private final CompletableFuture<String> slow;
        final AtomicInteger failures = new AtomicInteger();

        RenderStub(CompletableFuture<String> slow) {
            this.slow = slow;
        }

        @Override
        public CompletableFuture<String> render(String id) {
            return id.equals("slow") ? slow : CompletableFuture.completedFuture("rendered " + id);
        }

        @Override
        public CompletableFuture<String> fetch(String id) {
            if (failures.getAndDecrement() > 0) {
                return CompletableFuture.failedFuture(new IllegalStateException("not yet"));
            }
            return CompletableFuture.completedFuture("fetched " + id);
        }
    }

    // tag::testing-events[]
    @Test
    void eachRetryPublishesAnEvent() {
        ManualClock clock = new ManualClock();
        List<ResilienceEvent> events = new CopyOnWriteArrayList<>();
        ResilienceRegistry registry = ResilienceTesting.registry(clock)
                .listen(ResilienceEvent.class, events::add) // listeners are fixed at build()
                .build();
        AtomicInteger calls = new AtomicInteger();
        QuoteService quotes = Resilient.of(QuoteService.class, sku -> {
            if (calls.incrementAndGet() < 3) {
                throw new IllegalStateException("upstream down");
            }
            return "ok";
        }, registry);

        quotes.quote("A-1");

        assertThat(events).hasSize(2).allSatisfy(e -> assertThat(e).isInstanceOf(RetryEvent.Retrying.class));
        RetryEvent.Retrying first = (RetryEvent.Retrying) events.get(0);
        assertThat(first.attempt()).isEqualTo(1);
        assertThat(first.delay()).isEqualTo(Duration.ofSeconds(1));
        assertThat(first.id()).isEqualTo(
                "dev.resilience4cata.examples.docs.testing.QuoteService#quote(java.lang.String)");
    }
    // end::testing-events[]

    // tag::testing-snapshot[]
    @Test
    void theSnapshotShowsTheWindow() {
        ManualClock clock = new ManualClock();
        ResilienceRegistry registry = ResilienceTesting.registry(clock).build();
        InventoryService inventory = Resilient.of(InventoryService.class, sku -> {
            throw new IllegalStateException("inventory down");
        }, registry);

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> inventory.stock("X")).isInstanceOf(IllegalStateException.class);
        }

        CircuitBreakerSnapshot snapshot = registry.circuitBreaker("inventory").snapshot();
        assertThat(snapshot.state()).isEqualTo(CircuitBreakerState.CLOSED);
        assertThat(snapshot.bufferedCalls()).isEqualTo(2);
        assertThat(snapshot.failedCalls()).isEqualTo(2);
        assertThat(snapshot.failureRate()).isEqualTo(-1f); // not evaluated before minimumCalls = 4
    }
    // end::testing-snapshot[]
}
