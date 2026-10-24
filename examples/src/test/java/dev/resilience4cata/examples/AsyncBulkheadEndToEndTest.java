package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.BulkheadFullException;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AsyncBulkheadEndToEndTest {

    private static final String PREFIX = "dev.resilience4cata.examples.AsyncBulkheadClient#";

    private final ResilienceRegistry registry = ResilienceRegistry.builder().build();
    private final Map<String, CompletableFuture<String>> pending = new ConcurrentHashMap<>();
    private final List<String> started = new CopyOnWriteArrayList<>();

    private final AsyncBulkheadClient client = Resilient.of(AsyncBulkheadClient.class, new AsyncBulkheadClient() {
        @Override
        public CompletableFuture<String> fetch(String id) {
            return start(id);
        }

        @Override
        public CompletableFuture<String> fetchBriefly(String id) {
            return start(id);
        }

        private CompletableFuture<String> start(String id) {
            started.add(id);
            CompletableFuture<String> stage = new CompletableFuture<>();
            pending.put(id, stage);
            return stage;
        }
    }, registry);

    private void finish(String id) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!pending.containsKey(id) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        pending.get(id).complete("done " + id);
    }

    @Test
    void waitingCallersGetTheSlotInOrderWithoutBlockingTheCaller() throws Exception {
        CompletableFuture<String> first = client.fetch("a");
        long start = System.nanoTime();
        CompletableFuture<String> second = client.fetch("b");
        CompletableFuture<String> third = client.fetch("c");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).as("queued, not blocked").isLessThan(Duration.ofSeconds(1));
        assertThat(started).containsExactly("a");
        assertThat(registry.bulkhead(PREFIX + "fetch(java.lang.String)").concurrentCalls()).isEqualTo(1);

        finish("a");
        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("done a");
        finish("b");
        assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("done b");
        finish("c");
        assertThat(third.get(5, TimeUnit.SECONDS)).isEqualTo("done c");
        assertThat(started).containsExactly("a", "b", "c");
    }

    @Test
    void aCallerThatWaitsLongerThanMaxWaitIsRejected() throws Exception {
        CompletableFuture<String> holder = client.fetchBriefly("hold");
        CompletableFuture<String> waiter = client.fetchBriefly("late");
        Throwable failure = waiter.handle((value, f) -> f).get(5, TimeUnit.SECONDS);
        assertThat(failure).isInstanceOf(BulkheadFullException.class);

        finish("hold");
        assertThat(holder.get(5, TimeUnit.SECONDS)).isEqualTo("done hold");
        assertThat(started).as("the rejected call never ran").containsExactly("hold");
        assertThat(client.fetchBriefly("next")).isNotDone();
        assertThat(started).containsExactly("hold", "next");
    }
}
