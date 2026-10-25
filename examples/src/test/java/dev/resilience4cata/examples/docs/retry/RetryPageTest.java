package dev.resilience4cata.examples.docs.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.Pipeline;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.RetryConfig;
import dev.resilience4cata.RetrySnapshot;
import dev.resilience4cata.event.RetryEvent;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Every claim and snippet of {@code docs/en/policies/retry.md}. */
class RetryPageTest {

    @Test
    void wrapping() {
        InventoryApi backend = sku -> 7;
        // tag::retry-wrap[]
        ResilienceRegistry registry = ResilienceRegistry.builder().build();
        InventoryApi inventory = Resilient.of(InventoryApi.class, backend, registry);

        int units = inventory.stock("sku-1");   // up to 3 attempts
        // end::retry-wrap[]
        assertThat(units).isEqualTo(7);
    }

    @Test
    void testingOnAManualClock() {
        // tag::retry-testing[]
        ManualClock clock = new ManualClock();
        ResilienceRegistry registry = ResilienceTesting.registry(clock)
                .retryDefaults(r -> r.jitter(RetryConfig.Jitter.NONE))   // exact delays in this test
                .build();
        AtomicInteger calls = new AtomicInteger();
        InventoryApi backend = sku -> {
            if (calls.incrementAndGet() <= 2) {
                throw new IllegalStateException("inventory down");
            }
            return 7;
        };
        InventoryApi inventory = Resilient.of(InventoryApi.class, backend, registry);

        assertThat(inventory.stock("sku-1")).isEqualTo(7);   // returns at once, in real time
        assertThat(calls).hasValue(3);
        assertThat(clock.elapsed()).isEqualTo(Duration.ofMillis(300));   // 100 ms, then 200 ms
        // end::retry-testing[]
    }

    @Test
    void theLastFailureIsRethrownOnceTheAttemptsRunOut() {
        ManualClock clock = new ManualClock();
        ResilienceRegistry registry = ResilienceTesting.registry(clock).build();
        AtomicInteger calls = new AtomicInteger();
        InventoryApi inventory = Resilient.of(InventoryApi.class, sku -> {
            throw new IllegalStateException("down " + calls.incrementAndGet());
        }, registry);

        assertThatThrownBy(() -> inventory.stock("x")).isInstanceOf(IllegalStateException.class).hasMessage("down 3");
        // full jitter (the default): each delay is between 0 and the computed one
        assertThat(clock.elapsed()).isLessThanOrEqualTo(Duration.ofMillis(300));
    }

    @Test
    void errorsAreNotRetriedByDefault() {
        ResilienceRegistry registry = ResilienceTesting.registry(new ManualClock()).build();
        AtomicInteger calls = new AtomicInteger();
        InventoryApi inventory = Resilient.of(InventoryApi.class, sku -> {
            calls.incrementAndGet();
            throw new AssertionError("an Error");
        }, registry);

        assertThatThrownBy(() -> inventory.stock("x")).isInstanceOf(AssertionError.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void disabledMeansOneAttempt() {
        ResilienceRegistry registry = ResilienceTesting.registry(new ManualClock())
                .retryDefaults(r -> r.enabled(false)).build();
        AtomicInteger calls = new AtomicInteger();
        InventoryApi inventory = Resilient.of(InventoryApi.class, sku -> {
            calls.incrementAndGet();
            throw new IllegalStateException();
        }, registry);

        assertThatThrownBy(() -> inventory.stock("x")).isInstanceOf(IllegalStateException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void exceptionListsAndTheTimeBudget() throws IOException {
        ManualClock clock = new ManualClock();
        List<RetryEvent.GaveUp> gaveUp = new CopyOnWriteArrayList<>();
        ResilienceRegistry registry = ResilienceTesting.registry(clock)
                .retryDefaults(r -> r.jitter(RetryConfig.Jitter.NONE))
                .listen(RetryEvent.GaveUp.class, gaveUp::add)
                .build();
        List<Long> callTimes = new CopyOnWriteArrayList<>();
        Failure failure = new Failure();
        DocumentStore store = Resilient.of(DocumentStore.class, path -> {
            callTimes.add(clock.elapsed().toMillis());
            return failure.fail();
        }, registry);

        // retryOn: IOException is retried; delays 500 ms, 1 s, 2 s, then 2 s more would pass maxDuration
        failure.kind = "io";
        assertThatThrownBy(() -> store.download("a")).isInstanceOf(IOException.class);
        assertThat(callTimes).containsExactly(0L, 500L, 1500L, 3500L);
        assertThat(gaveUp.getLast().reason()).isEqualTo(RetryEvent.Reason.MAX_DURATION);
        assertThat(gaveUp.getLast().attempts()).isEqualTo(4);

        // abortOn wins over retryOn
        callTimes.clear();
        failure.kind = "missing";
        assertThatThrownBy(() -> store.download("b")).isInstanceOf(FileNotFoundException.class);
        assertThat(callTimes).hasSize(1);
        assertThat(gaveUp.getLast().reason()).isEqualTo(RetryEvent.Reason.NOT_RETRYABLE);

        // not in retryOn: not retried
        callTimes.clear();
        failure.kind = "state";
        assertThatThrownBy(() -> store.download("c")).isInstanceOf(IllegalStateException.class);
        assertThat(callTimes).hasSize(1);

        // an annotation list replaces the default list
        RetrySnapshot snapshot = registry.snapshot().retries().getFirst();
        assertThat(snapshot.instance())
                .isEqualTo("dev.resilience4cata.examples.docs.retry.DocumentStore#download(java.lang.String)");
        assertThat(snapshot.config().abortOn()).containsExactly(FileNotFoundException.class);
    }

    @Test
    void anOpenBreakerIsNeverRetried() {
        ManualClock clock = new ManualClock();
        List<RetryEvent.GaveUp> gaveUp = new CopyOnWriteArrayList<>();
        ResilienceRegistry registry = ResilienceTesting.registry(clock)
                .listen(RetryEvent.GaveUp.class, gaveUp::add)
                .build();
        AtomicInteger calls = new AtomicInteger();
        ReportApi reports = Resilient.of(ReportApi.class, id -> {
            calls.incrementAndGet();
            throw new IllegalStateException("reports down");
        }, registry);

        // two failures open the breaker; the third attempt is rejected, and abortOn does not list the rejection
        assertThatThrownBy(() -> reports.report("r-1")).isInstanceOf(CallNotPermittedException.class);
        assertThat(calls).hasValue(2);
        assertThat(gaveUp.getLast().attempts()).isEqualTo(3);
        assertThat(gaveUp.getLast().reason()).isEqualTo(RetryEvent.Reason.NOT_RETRYABLE);

        // while it is open, one attempt and no delay
        Duration before = clock.elapsed();
        assertThatThrownBy(() -> reports.report("r-2")).isInstanceOf(CallNotPermittedException.class);
        assertThat(calls).hasValue(2);
        assertThat(gaveUp.getLast().attempts()).isEqualTo(1);
        assertThat(clock.elapsed()).isEqualTo(before);
    }

    @Test
    void aRetryBudgetCapsTheRetriesOfADependencyThatIsDown() {
        // tag::retry-budget-testing[]
        ManualClock clock = new ManualClock();
        List<RetryEvent.GaveUp> gaveUp = new CopyOnWriteArrayList<>();
        ResilienceRegistry registry = ResilienceTesting.registry(clock)
                .listen(RetryEvent.GaveUp.class, gaveUp::add)
                .build();
        AtomicInteger attempts = new AtomicInteger();
        CatalogApi catalog = Resilient.of(CatalogApi.class, id -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("catalog down");
        }, registry);

        for (int i = 0; i < 100; i++) {
            assertThatThrownBy(() -> catalog.product("p-1")).isInstanceOf(IllegalStateException.class);
        }

        assertThat(attempts).hasValue(130);   // 100 calls + 10 (1/s over 10 s) + 20 (20% of 100), not 300
        assertThat(gaveUp).filteredOn(e -> e.reason() == RetryEvent.Reason.BUDGET_EXHAUSTED).hasSize(95);
        assertThat(registry.snapshot().retries().getFirst().budget())
                .isEqualTo(new RetrySnapshot.Budget(100, 30, 30));   // calls, retries, allowed retries
        // end::retry-budget-testing[]
        assertThat(gaveUp).filteredOn(e -> e.reason() == RetryEvent.Reason.MAX_ATTEMPTS).hasSize(5);
        assertThat(registry.snapshot().retries().getFirst().instance()).isEqualTo("catalog");
    }

    @Test
    void aFixedDelayAboveTheDefaultMaxDelayIsRejectedWhenWrapping() {
        ResilienceRegistry registry = ResilienceRegistry.builder().build();
        assertThatThrownBy(() -> Resilient.of(SlowPoller.class, new SlowPoller() {
            @Override
            public String poll(String id) {
                return id;
            }

            @Override
            public String pollFixed(String id) {
                return id;
            }
        }, registry)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("R4C-REG-003").hasMessageContaining("maxDelay");
    }

    @Test
    void longFixedDelaysNeedAMaxDelay() {
        ResilienceRegistry.Builder builder = ResilienceRegistry.builder();
        assertThatThrownBy(() -> builder.retryDefaults(r -> r.backoff(RetryConfig.Backoff.FIXED)
                .delay(Duration.ofSeconds(20))).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDelay");
    }

    @Test
    void asyncRetriesWaitWithoutBlocking() {
        ManualClock clock = new ManualClock();
        ResilienceRegistry registry = ResilienceTesting.registry(clock)
                .retryDefaults(r -> r.jitter(RetryConfig.Jitter.NONE)).build();
        AtomicInteger calls = new AtomicInteger();
        AsyncInventoryApi inventory = Resilient.of(AsyncInventoryApi.class, sku -> calls.incrementAndGet() <= 2
                ? CompletableFuture.failedFuture(new IllegalStateException("down"))
                : CompletableFuture.completedFuture(7), registry);

        CompletableFuture<Integer> units = inventory.stock("sku-1");
        assertThat(units).isNotDone();
        clock.advance(Duration.ofMillis(100));
        assertThat(units).isNotDone();
        clock.advance(Duration.ofMillis(200));
        assertThat(units).isCompletedWithValue(7);
        assertThat(calls).hasValue(3);
    }

    @Test
    void pipeline() {
        ResilienceRegistry registry = ResilienceTesting.registry(new ManualClock()).build();
        AtomicInteger calls = new AtomicInteger();
        InventoryApi backend = sku -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("down");
            }
            return 7;
        };
        // tag::retry-pipeline[]
        Pipeline<Integer> stock = Pipeline.builder(registry, "inventory", Integer.class)
                .retry(r -> r.maxAttempts(3).delay(Duration.ofMillis(100)))
                .build();

        int units = stock.execute(() -> backend.stock("sku-1"));
        // end::retry-pipeline[]
        assertThat(units).isEqualTo(7);
        assertThat(calls).hasValue(2);
    }

    private static final class Failure {
        volatile String kind = "io";

        byte[] fail() throws IOException {
            switch (kind) {
                case "io" -> throw new IOException("connection reset");
                case "missing" -> throw new FileNotFoundException("no such document");
                default -> throw new IllegalStateException("bug");
            }
        }
    }
}
