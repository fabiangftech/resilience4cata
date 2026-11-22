package dev.resilience4cata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The policies that block the caller (a timeout's interrupt, the waits of a bulkhead and a rate limiter, a retry's
 * pause) on real clocks, each run once on a platform thread and once on a virtual thread, which parks and is
 * interrupted through another path (Resilience4j runs its tests in both modes since virtual threads became common).
 * The unit tests of each policy use fake clocks and the test's own (platform) thread; these check that the blocking
 * itself behaves the same in both modes. Bounds on elapsed time are lower bounds, or upper bounds far above the
 * expected time, so that a slow machine does not fail them.
 */
class BlockingPoliciesThreadModeTest {

    /** The kind of thread that makes the protected call. */
    enum ThreadMode {
        PLATFORM, VIRTUAL;

        Thread.Builder builder() {
            return this == PLATFORM ? Thread.ofPlatform().daemon() : Thread.ofVirtual();
        }
    }

    /** A protected call running on its own thread of one mode, which the test can interrupt. */
    private static final class Caller<T> {
        private final CompletableFuture<T> result = new CompletableFuture<>();
        private final Thread thread;

        Caller(ThreadMode mode, Callable<T> call) {
            thread = mode.builder().start(() -> {
                try {
                    result.complete(call.call());
                } catch (Throwable t) {
                    result.completeExceptionally(t);
                }
            });
        }

        /** Waits until the caller is parked (waiting for a slot, a permit or the end of a pause). */
        Caller<T> awaitParked() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (thread.getState() != Thread.State.WAITING && thread.getState() != Thread.State.TIMED_WAITING) {
                assertThat(System.nanoTime() - deadline).as("the caller never parked").isNegative();
                Thread.sleep(1);
            }
            return this;
        }

        void interrupt() {
            thread.interrupt();
        }

        T get() throws Exception {
            try {
                return result.get(30, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                throw (Exception) e.getCause();
            }
        }

        Throwable failure() {
            return catchThrowable(this::get);
        }
    }

    private static <T> T in(ThreadMode mode, Callable<T> call) throws Exception {
        return new Caller<>(mode, call).get();
    }

    private final ResilienceRegistry registry = ResilienceRegistry.builder().build();

    // --- Timeout

    @ParameterizedTest
    @EnumSource(ThreadMode.class)
    void aTimeoutInterruptsASleepingCallAndLeavesTheThreadUninterrupted(ThreadMode mode) throws Exception {
        Pipeline<String> pipeline = Pipeline.builder(registry, "timeout-sleep", String.class)
                .timeout(t -> t.timeout(Duration.ofMillis(50))).build();

        String after = in(mode, () -> {
            long start = System.nanoTime();
            Throwable thrown = catchThrowable(() -> pipeline.execute(() -> {
                Thread.sleep(Duration.ofSeconds(30));
                return "too late";
            }));
            assertThat(thrown).isInstanceOf(CallTimeoutException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(Duration.ofMillis(50), Duration.ofSeconds(10));
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            Thread.sleep(20); // would throw if the watchdog's interrupt had been left behind
            return pipeline.execute(() -> "in time");
        });

        assertThat(after).isEqualTo("in time");
    }

    @ParameterizedTest
    @EnumSource(ThreadMode.class)
    void aTimeoutInterruptsACallBlockedOnAQueue(ThreadMode mode) throws Exception {
        Pipeline<String> pipeline = Pipeline.builder(registry, "timeout-queue", String.class)
                .timeout(t -> t.timeout(Duration.ofMillis(50))).build();
        ArrayBlockingQueue<String> empty = new ArrayBlockingQueue<>(1);

        Throwable thrown = catchThrowable(() -> in(mode, () -> pipeline.execute(empty::take)));

        assertThat(thrown).isInstanceOf(CallTimeoutException.class);
    }

    @ParameterizedTest
    @EnumSource(ThreadMode.class)
    void aCallThatIgnoresTheInterruptEndsInATimeoutWithTheInterruptCleared(ThreadMode mode) throws Exception {
        Pipeline<String> pipeline = Pipeline.builder(registry, "timeout-busy", String.class)
                .timeout(t -> t.timeout(Duration.ofMillis(50))).build();

        boolean interruptedAfter = in(mode, () -> {
            Throwable thrown = catchThrowable(() -> pipeline.execute(() -> {
                long end = System.nanoTime() + Duration.ofMillis(300).toNanos();
                while (System.nanoTime() - end < 0) {
                    Thread.onSpinWait(); // never looks at the interrupt, so the watchdog's stays pending
                }
                return "late";
            }));
            assertThat(thrown).isInstanceOf(CallTimeoutException.class);
            return Thread.currentThread().isInterrupted();
        });

        assertThat(interruptedAfter).as("the watchdog's interrupt is cleared").isFalse();
    }

    @ParameterizedTest
    @EnumSource(ThreadMode.class)
    void manyTimedCallsThatEndInTimeAreNeverInterrupted(ThreadMode mode) throws Exception {
        Pipeline<Integer> pipeline = Pipeline.builder(registry, "timeout-many", Integer.class)
                .timeout(t -> t.timeout(Duration.ofSeconds(5))).build();

        int done = in(mode, () -> {
            int count = 0;
            for (int i = 0; i < 500; i++) {
                int n = i;
                count += pipeline.execute(() -> {
                    if (n % 50 == 0) {
                        Thread.sleep(1); // park now and then: a virtual thread may come back on another carrier
                    }
                    return 1;
                });
                assertThat(Thread.currentThread().isInterrupted()).isFalse();
            }
            return count;
        });

        assertThat(done).isEqualTo(500);
    }

    // --- Bulkhead with maxWait

    @ParameterizedTest
    @EnumSource(ThreadMode.class)
    void aWaitingCallGetsTheSlotWhenItIsFreed(ThreadMode mode) throws Exception {
        Pipeline<String> pipeline = Pipeline.builder(registry, "bulkhead-handoff", String.class)
                .bulkhead(b -> b.maxConcurrent(1).maxWait(Duration.ofSeconds(20))).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Caller<String> holder = new Caller<>(mode, () -> pipeline.execute(() -> {
            entered.countDown();
            release.await();
            return "held";
        }));
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        Caller<String> waiter = new Caller<>(mode, () -> pipeline.execute(() -> "waited")).awaitParked();
        release.countDown();

        assertThat(holder.get()).isEqualTo("held");
        assertThat(waiter.get()).isEqualTo("waited");
        assertThat(registry.bulkhead("bulkhead-handoff").concurrentCalls()).isZero();
    }

    @ParameterizedTest
    @EnumSource(ThreadMode.class)
    void aWaitThatEndsWithoutASlotIsARejection(ThreadMode mode) throws Exception {
        Pipeline<String> pipeline = Pipeline.builder(registry, "bulkhead-timeout", String.class)
                .bulkhead(b -> b.maxConcurrent(1).maxWait(Duration.ofMillis(50))).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Caller<String> holder = new Caller<>(mode, () -> pipeline.execute(() -> {
            entered.countDown();
            release.await();
            return "held";
        }));
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        long start = System.nanoTime();
        Throwable thrown = catchThrowable(() -> in(mode, () -> pipeline.execute(() -> "never")));

        assertThat(thrown).isInstanceOf(BulkheadFullException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(50));
        release.countDown();
        assertThat(holder.get()).isEqualTo("held");
    }

    @ParameterizedTest
    @EnumSource(ThreadMode.class)
    void anInterruptedWaitIsARejectionThatKeepsTheInterrupt(ThreadMode mode) throws Exception {
        Pipeline<String> pipeline = Pipeline.builder(registry, "bulkhead-interrupt", String.class)
                .bulkhead(b -> b.maxConcurrent(1).maxWait(Duration.ofSeconds(20))).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Caller<String> holder = new Caller<>(mode, () -> pipeline.execute(() -> {
            entered.countDown();
            release.await();
            return "held";
        }));
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        Caller<Boolean> waiter = new Caller<>(mode, () -> {
            assertThat(catchThrowable(() -> pipeline.execute(() -> "never"))).isInstanceOf(BulkheadFullException.class);
            return Thread.currentThread().isInterrupted();
        }).awaitParked();
        waiter.interrupt();

        assertThat(waiter.get()).as("interrupt flag after the rejection").isTrue();
        release.countDown();
        assertThat(holder.get()).isEqualTo("held");
        assertThat(registry.bulkhead("bulkhead-interrupt").concurrentCalls()).isZero();
    }

    // --- Rate limiter with maxWait

    @ParameterizedTest
    @EnumSource(ThreadMode.class)
    void aCallWaitsForItsPermit(ThreadMode mode) throws Exception {
        Pipeline<String> pipeline = Pipeline.builder(registry, "limiter-wait", String.class)
                .rateLimiter(r -> r.permits(1).period(Duration.ofMillis(200)).maxWait(Duration.ofSeconds(10))).build();

        Duration second = in(mode, () -> {
            pipeline.execute(() -> "first");
            long start = System.nanoTime();
            pipeline.execute(() -> "second");
            return Duration.ofNanos(System.nanoTime() - start);
        });

        assertThat(second).isGreaterThanOrEqualTo(Duration.ofMillis(150));
    }

    @ParameterizedTest
    @EnumSource(ThreadMode.class)
    void anInterruptedWaitGivesThePermitBack(ThreadMode mode) throws Exception {
        Pipeline<String> pipeline = Pipeline.builder(registry, "limiter-interrupt", String.class)
                .rateLimiter(r -> r.permits(1).period(Duration.ofSeconds(1)).maxWait(Duration.ofSeconds(20))).build();
        long first = System.nanoTime();
        assertThat(in(mode, () -> pipeline.execute(() -> "first"))).isEqualTo("first");

        Caller<Boolean> waiter = new Caller<>(mode, () -> {
            assertThat(catchThrowable(() -> pipeline.execute(() -> "never")))
                    .isInstanceOf(RateLimitExceededException.class);
            return Thread.currentThread().isInterrupted();
        }).awaitParked();
        waiter.interrupt();
        assertThat(waiter.get()).as("interrupt flag after the rejection").isTrue();

        // The permit the interrupted call had reserved is free again: one permit is available 1 s after the first
        // call. Had it been kept, the next would only be available 2 s after.
        Thread.sleep(Math.max(0, Duration.ofMillis(1200).toMillis() - Duration.ofNanos(System.nanoTime() - first).toMillis()));
        assertThat(registry.rateLimiter("limiter-interrupt").availablePermits()).isEqualTo(1);
    }

    // --- Retry pauses

    @ParameterizedTest
    @EnumSource(ThreadMode.class)
    void aRetryPausesBetweenAttempts(ThreadMode mode) throws Exception {
        Pipeline<String> pipeline = Pipeline.builder(registry, "retry-pause", String.class)
                .retry(r -> r.maxAttempts(3).delay(Duration.ofMillis(50)).backoff(RetryConfig.Backoff.FIXED)
                        .jitter(RetryConfig.Jitter.NONE))
                .build();
        AtomicInteger attempts = new AtomicInteger();

        Duration elapsed = in(mode, () -> {
            long start = System.nanoTime();
            assertThat(catchThrowable(() -> pipeline.execute(() -> {
                attempts.incrementAndGet();
                throw new IOException("down");
            }))).isInstanceOf(IOException.class);
            return Duration.ofNanos(System.nanoTime() - start);
        });

        assertThat(attempts).hasValue(3);
        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(100));
    }

    @ParameterizedTest
    @EnumSource(ThreadMode.class)
    void anInterruptDuringAPauseStopsTheRetriesAndIsKept(ThreadMode mode) throws Exception {
        Pipeline<String> pipeline = Pipeline.builder(registry, "retry-interrupt", String.class)
                .retry(r -> r.maxAttempts(5).delay(Duration.ofSeconds(5)).backoff(RetryConfig.Backoff.FIXED)
                        .jitter(RetryConfig.Jitter.NONE))
                .build();
        AtomicInteger attempts = new AtomicInteger();

        Caller<Boolean> caller = new Caller<>(mode, () -> {
            assertThat(catchThrowable(() -> pipeline.execute(() -> {
                attempts.incrementAndGet();
                throw new IOException("down");
            }))).isInstanceOf(IOException.class).hasMessage("down");
            return Thread.currentThread().isInterrupted();
        }).awaitParked();
        caller.interrupt();

        assertThat(caller.get()).as("interrupt flag after giving up").isTrue();
        assertThat(attempts).hasValue(1);
    }
}
