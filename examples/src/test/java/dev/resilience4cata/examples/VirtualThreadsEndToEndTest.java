package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Synchronous protected methods called from thousands of virtual threads, as on a server that runs every request on
 * its own (Spring's {@code spring.threads.virtual.enabled}, Micronaut's and Quarkus's virtual-thread executors). A
 * timeout interrupts a blocked virtual thread and leaves no interrupt behind, the bulkhead's and rate limiter's waits
 * and the retry's pauses park virtual threads without exhausting the carriers, and no policy pins a virtual thread to
 * its carrier while it blocks (a JFR recording of {@code jdk.VirtualThreadPinned} events with no threshold).
 */
class VirtualThreadsEndToEndTest {

    private final ResilienceRegistry registry = ResilienceRegistry.builder().build();
    private final AtomicInteger inside = new AtomicInteger();
    private final AtomicInteger maxInside = new AtomicInteger();
    private final AtomicInteger interrupted = new AtomicInteger();
    private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
    private final VirtualThreadClient client = Resilient.of(VirtualThreadClient.class, new Backend(), registry);
    private ExecutorService threads;
    private Recording pinning;

    /** Blocks like a remote call: {@code slow} for five seconds, the others for a few milliseconds. */
    private final class Backend implements VirtualThreadClient {

        private String block(String id, long millis) {
            int now = inside.incrementAndGet();
            maxInside.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(millis);
                return "ok " + id;
            } catch (InterruptedException e) {
                interrupted.incrementAndGet();
                Thread.currentThread().interrupt();
                return "interrupted";
            } finally {
                inside.decrementAndGet();
            }
        }

        @Override
        public String slow(String id) {
            return block(id, 5_000);
        }

        @Override
        public String queued(String id) {
            return block(id, 5);
        }

        @Override
        public String limited(String id) {
            return block(id, 1);
        }

        @Override
        public String retried(String id) {
            if (attempts.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet() < 3) {
                throw new IllegalStateException("down");
            }
            return block(id, 1);
        }

        @Override
        public String stacked(String id) {
            if (attempts.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet() < 2) {
                throw new IllegalStateException("down");
            }
            return block(id, 5);
        }
    }

    @BeforeEach
    void start() {
        threads = Executors.newVirtualThreadPerTaskExecutor();
        pinning = new Recording();
        pinning.enable("jdk.VirtualThreadPinned").withThreshold(Duration.ZERO).withStackTrace();
        pinning.start();
    }

    @AfterEach
    void stop() throws Exception {
        threads.close();
        pinning.stop();
        Path file = Files.createTempFile("pinning", ".jfr");
        try {
            pinning.dump(file);
            List<String> ours = new ArrayList<>();
            for (RecordedEvent event : RecordingFile.readAllEvents(file)) {
                if (event.getStackTrace() != null && event.getStackTrace().getFrames().stream()
                        .map(RecordedFrame::getMethod).anyMatch(m -> m.getType().getName().startsWith("dev.resilience4cata.")
                                && !m.getType().getName().startsWith("dev.resilience4cata.examples."))) {
                    ours.add(event.toString());
                }
            }
            assertThat(ours).as("virtual threads pinned while blocking in a policy").isEmpty();
        } finally {
            pinning.close();
            Files.deleteIfExists(file);
        }
    }

    /** Runs {@code calls} calls at once, each on its own virtual thread, and returns what each returned or threw. */
    private List<Object> run(int calls, Function<String, String> call) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> results = new ArrayList<>();
        for (int i = 0; i < calls; i++) {
            String id = "id-" + i;
            results.add(threads.submit(() -> {
                go.await();
                try {
                    String value = call.apply(id);
                    return Thread.currentThread().isInterrupted() ? "left interrupted" : value;
                } catch (RuntimeException e) {
                    return Thread.currentThread().isInterrupted() ? "left interrupted" : e;
                }
            }));
        }
        go.countDown();
        List<Object> outcomes = new ArrayList<>();
        for (Future<Object> result : results) {
            outcomes.add(result.get(60, TimeUnit.SECONDS));
        }
        return outcomes;
    }

    @Test
    void timeoutsInterruptBlockedVirtualThreadsAtOnceAndLeaveNoInterrupt() throws Exception {
        long started = System.nanoTime();
        List<Object> outcomes = run(2_000, client::slow);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(outcomes).allSatisfy(o -> assertThat(o).isInstanceOf(CallTimeoutException.class));
        assertThat(took).as("2000 calls of five seconds, 100 ms timeout").isLessThan(Duration.ofSeconds(4));
        assertThat(interrupted).hasValue(2_000);
        assertThat(inside).hasValue(0);
    }

    @Test
    void aBulkheadWithMaxWaitQueuesVirtualThreadsAndLetsThemAllInInTurn() throws Exception {
        List<Object> outcomes = run(1_000, client::queued);

        assertThat(outcomes).allSatisfy(o -> assertThat(o).asString().startsWith("ok "));
        assertThat(maxInside.get()).isLessThanOrEqualTo(8);
        assertThat(registry.bulkhead("vt-bulkhead").concurrentCalls()).isZero();
        assertThat(registry.bulkhead("vt-bulkhead").waitingCalls()).isZero();
    }

    @Test
    void aRateLimiterWithMaxWaitSpacesVirtualThreadsOut() throws Exception {
        long started = System.nanoTime();
        List<Object> outcomes = run(1_500, client::limited);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(outcomes).allSatisfy(o -> assertThat(o).asString().startsWith("ok "));
        assertThat(took).as("500 at once, then 500 a second").isGreaterThanOrEqualTo(Duration.ofMillis(1_900));
    }

    @Test
    void retriesPauseVirtualThreadsWithoutHoldingCarriers() throws Exception {
        long started = System.nanoTime();
        List<Object> outcomes = run(5_000, client::retried);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(outcomes).allSatisfy(o -> assertThat(o).asString().startsWith("ok "));
        assertThat(attempts.values()).allSatisfy(a -> assertThat(a).hasValue(3));
        assertThat(took).as("5000 calls pausing 2 × 20 ms at once, not one carrier at a time")
                .isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void aStackOfPoliciesWorksOnVirtualThreads() throws Exception {
        List<Object> outcomes = run(2_000, client::stacked);

        assertThat(outcomes).allSatisfy(o -> assertThat(o).asString().startsWith("ok "));
        assertThat(attempts.values()).allSatisfy(a -> assertThat(a).hasValue(2));
        assertThat(maxInside.get()).isLessThanOrEqualTo(16);
        assertThat(registry.bulkhead("vt-stack").concurrentCalls()).isZero();
        assertThat(registry.adaptiveLimit("vt-stack").concurrentCalls()).isZero();
    }
}
