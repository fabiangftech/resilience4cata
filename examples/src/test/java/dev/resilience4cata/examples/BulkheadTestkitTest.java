package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.BulkheadFullException;
import dev.resilience4cata.BulkheadHandle;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * A synchronous {@code @Bulkhead} with {@code maxWait = "30s"} on a {@link ManualClock}: a caller
 * waiting for a slot gives up when the clock passes 30 s, however long that takes in real time, and
 * gets a slot as soon as one is released. Callers run on their own threads; the test waits for them
 * only in real time bounded by a few seconds, never for the 30 s.
 */
class BulkheadTestkitTest {

    private final ManualClock clock = new ManualClock();
    private final ResilienceRegistry registry = ResilienceTesting.registry(clock).build();
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final BulkheadTestkitClient client = Resilient.of(BulkheadTestkitClient.class, id -> {
        if (id.equals("slow")) {
            entered.countDown();
            await(release);
        }
        return "rendered " + id;
    }, registry);
    private final BulkheadHandle bulkhead = registry.bulkhead("renderer");

    @Test
    void aWaitingCallerIsRejectedWhenTheClockPassesMaxWait() throws Exception {
        CompletableFuture<String> slow = inBackground("slow");
        await(entered);
        CompletableFuture<String> waiting = inBackground("next");
        awaitWaiting(1);

        clock.advance(Duration.ofSeconds(29));
        assertThat(waiting).isNotDone();
        clock.advance(Duration.ofSeconds(1));

        assertThat(waiting.handle((v, e) -> e).get(5, TimeUnit.SECONDS)).isInstanceOf(BulkheadFullException.class);
        release.countDown();
        assertThat(slow.get(5, TimeUnit.SECONDS)).isEqualTo("rendered slow");
    }

    @Test
    void aWaitingCallerRunsAsSoonAsTheSlotIsReleased() throws Exception {
        CompletableFuture<String> slow = inBackground("slow");
        await(entered);
        CompletableFuture<String> waiting = inBackground("next");
        awaitWaiting(1);
        clock.advance(Duration.ofSeconds(20));

        release.countDown();

        assertThat(slow.get(5, TimeUnit.SECONDS)).isEqualTo("rendered slow");
        assertThat(waiting.get(5, TimeUnit.SECONDS)).isEqualTo("rendered next");
        assertThat(clock.elapsed()).isEqualTo(Duration.ofSeconds(20));
        assertThat(bulkhead.concurrentCalls()).isZero();
    }

    private CompletableFuture<String> inBackground(String id) {
        CompletableFuture<String> result = new CompletableFuture<>();
        Thread.ofPlatform().daemon().start(() -> {
            try {
                result.complete(client.render(id));
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    private void awaitWaiting(int calls) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (bulkhead.waitingCalls() < calls) {
            if (System.nanoTime() - deadline > 0) {
                throw new AssertionError("No caller started waiting");
            }
            Thread.onSpinWait();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out in real time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
