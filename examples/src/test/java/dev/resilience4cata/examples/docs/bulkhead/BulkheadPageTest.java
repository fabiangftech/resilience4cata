package dev.resilience4cata.examples.docs.bulkhead;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.BulkheadFullException;
import dev.resilience4cata.BulkheadHandle;
import dev.resilience4cata.BulkheadSnapshot;
import dev.resilience4cata.Pipeline;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Every claim and snippet of {@code docs/en/policies/bulkhead.md}. */
class BulkheadPageTest {

    private final ManualClock clock = new ManualClock();
    private final ResilienceRegistry registry = ResilienceTesting.registry(clock).build();
    private final CompletableFuture<byte[]> first = new CompletableFuture<>();
    private final CompletableFuture<byte[]> second = new CompletableFuture<>();
    private final ThumbnailService backend = new ThumbnailService() {
        @Override
        public byte[] thumbnail(String imageId) {
            return new byte[] {1};
        }

        @Override
        public CompletableFuture<byte[]> thumbnailAsync(String imageId) {
            return switch (imageId) {
                case "a" -> first;
                case "b" -> second;
                default -> CompletableFuture.completedFuture(new byte[] {3});
            };
        }
    };

    @Test
    void testingOnAManualClock() {
        // tag::bulkhead-testing[]
        ThumbnailService thumbnails = Resilient.of(ThumbnailService.class, backend, registry);
        BulkheadHandle bulkhead = registry.bulkhead("thumbnails-async");

        CompletableFuture<byte[]> a = thumbnails.thumbnailAsync("a");   // pending: holds a slot
        CompletableFuture<byte[]> b = thumbnails.thumbnailAsync("b");   // pending: holds the other
        CompletableFuture<byte[]> c = thumbnails.thumbnailAsync("c");   // full: waits up to 1 s
        assertThat(bulkhead.concurrentCalls()).isEqualTo(2);
        assertThat(bulkhead.waitingCalls()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(1));                          // nothing was released in time
        assertThat(c).failsWithin(Duration.ofSeconds(5))
                .withThrowableThat().withCauseInstanceOf(BulkheadFullException.class);
        // end::bulkhead-testing[]
        assertThat(a).isNotDone();
        assertThat(b).isNotDone();
    }

    @Test
    void aReleasedSlotGoesToTheOldestAsyncWaiter() throws Exception {
        ThumbnailService thumbnails = Resilient.of(ThumbnailService.class, backend, registry);
        BulkheadHandle bulkhead = registry.bulkhead("thumbnails-async");
        CompletableFuture<byte[]> a = thumbnails.thumbnailAsync("a");
        thumbnails.thumbnailAsync("b");
        CompletableFuture<byte[]> c = thumbnails.thumbnailAsync("c");

        first.complete(new byte[] {1});
        assertThat(a).isCompleted();
        assertThat(c.get(5, TimeUnit.SECONDS)).containsExactly(3);
        // c completed at once and gave its slot back; b still holds one
        awaitConcurrent(bulkhead, 1);
        assertThat(bulkhead.waitingCalls()).isZero();
    }

    @Test
    void aFullSyncBulkheadRejectsAtOnce() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ResilienceRegistry real = ResilienceRegistry.builder()
                .bulkheadInstance("dev.resilience4cata.examples.docs.bulkhead.ReportRenderer#render(java.lang.String)",
                        b -> b.maxConcurrent(1))
                .build();
        ReportRenderer renderer = Resilient.of(ReportRenderer.class, id -> {
            if (id.equals("slow")) {
                inside.countDown();
                await(release);
            }
            return "rendered " + id;
        }, real);
        Thread holder = Thread.ofVirtual().start(() -> renderer.render("slow"));
        await(inside);

        assertThatThrownBy(() -> renderer.render("fast"))
                .isInstanceOf(BulkheadFullException.class)
                .satisfies(e -> assertThat(((BulkheadFullException) e).instanceId())
                        .isEqualTo("dev.resilience4cata.examples.docs.bulkhead.ReportRenderer#render(java.lang.String)"));
        BulkheadHandle handle = real.bulkhead(
                "dev.resilience4cata.examples.docs.bulkhead.ReportRenderer#render(java.lang.String)");
        assertThat(handle.waitingCalls()).isZero(); // maxWait = 0: nobody waits

        release.countDown();
        holder.join(5_000);
        assertThat(renderer.render("fast")).isEqualTo("rendered fast");
        assertThat(handle.concurrentCalls()).isZero();
    }

    @Test
    void handle() {
        Resilient.of(ThumbnailService.class, backend, registry);
        // tag::bulkhead-handle[]
        BulkheadHandle bulkhead = registry.bulkhead("thumbnails");
        int running = bulkhead.concurrentCalls();        // calls inside right now
        int waiting = bulkhead.waitingCalls();           // calls waiting for a slot (0 when maxWait = 0)
        BulkheadSnapshot snapshot = bulkhead.snapshot(); // instance, concurrentCalls, waitingCalls, maxConcurrent
        // end::bulkhead-handle[]
        assertThat(running).isZero();
        assertThat(waiting).isZero();
        assertThat(snapshot.maxConcurrent()).isEqualTo(4);
        assertThat(snapshot.availableSlots()).isEqualTo(4);
    }

    @Test
    void pipeline() {
        ReportRenderer backend = id -> "rendered " + id;
        // tag::bulkhead-pipeline[]
        Pipeline<String> render = Pipeline.builder(registry, "renderer", String.class)
                .bulkhead(b -> b.maxConcurrent(10))
                .build();

        String report = render.execute(() -> backend.render("r-1"));
        // end::bulkhead-pipeline[]
        assertThat(report).isEqualTo("rendered r-1");
        assertThat(registry.bulkhead("renderer").config().maxConcurrent()).isEqualTo(10);
    }

    private static void awaitConcurrent(BulkheadHandle bulkhead, int calls) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (bulkhead.concurrentCalls() != calls && System.nanoTime() - deadline < 0) {
            Thread.sleep(1);
        }
        assertThat(bulkhead.concurrentCalls()).isEqualTo(calls);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out in real time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
