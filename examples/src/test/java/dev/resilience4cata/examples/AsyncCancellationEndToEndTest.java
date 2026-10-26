package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.Pipeline;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.event.ResilienceEvent;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Cancelling the future of a generated async method (or of {@code Pipeline.executeAsync}) cancels the
 * call inside it, and the policies let go at once: on a {@link ManualClock}, nothing waits for real time.
 */
class AsyncCancellationEndToEndTest {

    private final ManualClock clock = new ManualClock();
    private final List<ResilienceEvent> events = new CopyOnWriteArrayList<>();
    private final ResilienceRegistry registry = ResilienceTesting.registry(clock)
            .listen(ResilienceEvent.class, events::add).build();
    private final Map<String, CompletableFuture<String>> stages = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger recoveries = new AtomicInteger();
    private final AsyncCancelClient client = Resilient.of(AsyncCancelClient.class, new AsyncCancelClient() {
        @Override
        public CompletableFuture<String> held(String id) {
            return pending(id);
        }

        @Override
        public CompletableFuture<String> retried(String id) {
            calls.incrementAndGet();
            return CompletableFuture.failedFuture(new IOException("down"));
        }

        @Override
        public CompletionStage<String> guarded(String id) {
            return pending(id);
        }

        @Override
        public CompletableFuture<String> limited(String id) {
            return pending(id);
        }

        @Override
        public CompletableFuture<String> stack(String id) {
            return pending(id);
        }

        @Override
        public CompletableFuture<String> recover(String id, Throwable failure) {
            recoveries.incrementAndGet();
            return CompletableFuture.completedFuture("fallback " + id);
        }
    }, registry);

    private CompletableFuture<String> pending(String id) {
        calls.incrementAndGet();
        return stages.computeIfAbsent(id, key -> new CompletableFuture<>());
    }

    @Test
    void cancellingACallStuckInABulkheadFreesTheSlotAtOnce() throws Exception {
        CompletableFuture<String> stuck = client.held("stuck");
        CompletableFuture<String> next = client.held("next");
        assertThat(registry.bulkhead("cancel-bulkhead").concurrentCalls()).isOne();
        assertThat(registry.bulkhead("cancel-bulkhead").waitingCalls()).isOne();

        stuck.cancel(true);

        assertThat(stages.get("stuck")).as("the delegate's stage is cancelled").isCancelled();
        waitForStage("next"); // the slot went to the waiter, which starts on a virtual thread
        stages.get("next").complete("ok");
        assertThat(next.get(5, TimeUnit.SECONDS)).isEqualTo("ok");
        assertThat(registry.bulkhead("cancel-bulkhead").concurrentCalls()).isZero();
    }

    @Test
    void cancellingAWaitingCallTakesItOutOfTheQueue() {
        CompletableFuture<String> holder = client.held("holder");
        CompletableFuture<String> waiting = client.held("waiting");

        waiting.cancel(true);

        assertThat(registry.bulkhead("cancel-bulkhead").waitingCalls()).isZero();
        assertThat(clock.pendingTasks()).as("its maxWait timer is cancelled").isZero();
        stages.get("holder").complete("ok");
        assertThat(holder).isCompletedWithValue("ok");
        assertThat(stages).as("the cancelled call never ran").doesNotContainKey("waiting");
        assertThat(events).isEmpty();
    }

    @Test
    void cancellingDuringARetryDelayStartsNoFurtherAttemptAndNoFallback() {
        CompletableFuture<String> call = client.retried("a");
        assertThat(calls).hasValue(1);
        assertThat(clock.pendingTasks()).as("the one-second backoff").isOne();
        events.clear();

        call.cancel(true);

        assertThat(clock.pendingTasks()).isZero();
        clock.advance(Duration.ofMinutes(1));
        assertThat(calls).hasValue(1);
        assertThat(recoveries).as("nobody waits for a fallback").hasValue(0);
        assertThat(call).isCancelled();
        assertThat(events).as("no GaveUp").isEmpty();
    }

    @Test
    void aCancelledCallIsNotABreakerFailure() {
        CompletableFuture<String> call = client.guarded("a").toCompletableFuture();

        call.cancel(true);

        assertThat(stages.get("a")).isCancelled();
        assertThat(registry.circuitBreaker("cancel-breaker").state()).as("one failure would open it")
                .isEqualTo(CircuitBreakerState.CLOSED);
        assertThat(registry.circuitBreaker("cancel-breaker").snapshot().failedCalls()).isZero();
    }

    @Test
    void aCallCancelledBeforeItsPermitIsDueNeverRunsAndGivesThePermitBack() {
        CompletableFuture<String> first = client.limited("first");
        CompletableFuture<String> second = client.limited("second");
        assertThat(stages).containsOnlyKeys("first");

        second.cancel(true);
        clock.advance(Duration.ofSeconds(1));

        assertThat(stages).as("never started").containsOnlyKeys("first");
        CompletableFuture<String> third = client.limited("third");
        assertThat(stages).as("the returned permit lets the next call start at once").containsKey("third");
        assertThat(first).isNotDone();
        assertThat(third).isNotDone();
    }

    @Test
    void aCancelGoesThroughEveryLayerOfAStack() {
        CompletableFuture<String> call = client.stack("a");
        assertThat(registry.bulkhead("cancel-stack").concurrentCalls()).isOne();

        call.cancel(true);

        assertThat(stages.get("a")).isCancelled();
        assertThat(registry.bulkhead("cancel-stack").concurrentCalls()).isZero();
        assertThat(registry.circuitBreaker("cancel-stack").state()).isEqualTo(CircuitBreakerState.CLOSED);
        assertThat(clock.pendingTasks()).as("no timeout, no backoff left").isZero();
        clock.advance(Duration.ofMinutes(1));
        assertThat(calls).hasValue(1);
        assertThat(recoveries).hasValue(0);
        assertThat(events).isEmpty();
    }

    @Test
    void cancellingAPipelineCallReachesTheCallToo() {
        Pipeline<String> pipeline = Pipeline.builder(registry, "cancel-pipeline", String.class)
                .retry().circuitBreaker().timeout().bulkhead()
                .fallback(failure -> "fallback").build();
        CompletableFuture<String> stage = new CompletableFuture<>();
        CompletableFuture<String> call = pipeline.executeAsync(() -> stage);
        assertThat(registry.bulkhead("cancel-pipeline").concurrentCalls()).isOne();

        call.cancel(true);

        assertThat(stage).isCancelled();
        assertThat(registry.bulkhead("cancel-pipeline").concurrentCalls()).isZero();
        assertThat(registry.circuitBreaker("cancel-pipeline").snapshot().failedCalls()).isZero();
        assertThat(clock.pendingTasks()).isZero();
        assertThat(events).isEmpty();

        Pipeline<String> bulkheadOnly = Pipeline.builder(registry, "cancel-pipeline-bulkhead", String.class)
                .bulkhead().build();
        CompletableFuture<String> held = new CompletableFuture<>();
        bulkheadOnly.executeAsync(() -> held).cancel(true);
        assertThat(held).as("the outermost policy's future reaches the caller as is").isCancelled();
        assertThat(registry.bulkhead("cancel-pipeline-bulkhead").concurrentCalls()).isZero();
    }

    private void waitForStage(String id) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!stages.containsKey(id) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(stages).containsKey(id);
    }
}
