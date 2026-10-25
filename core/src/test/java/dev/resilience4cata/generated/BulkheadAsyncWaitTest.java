package dev.resilience4cata.generated;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.BulkheadConfig;
import dev.resilience4cata.BulkheadFullException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The non-blocking wait of {@link BulkheadPolicy#executeAsync}: hand-off, timeouts and their races. */
class BulkheadAsyncWaitTest {

    private static BulkheadPolicy bulkhead(int maxConcurrent, Duration maxWait) {
        return new BulkheadPolicy("test", BulkheadConfig.builder().maxConcurrent(maxConcurrent).maxWait(maxWait).build());
    }

    private static Throwable failureOf(CompletableFuture<?> future) {
        assertThat(future).isCompletedExceptionally();
        return future.handle((value, failure) -> failure).join();
    }

    /** A delegate whose stage the test completes, recording when and on which thread it was called. */
    private static final class Probe implements AsyncCall<String> {
        final CompletableFuture<String> stage = new CompletableFuture<>();
        final CountDownLatch called = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        volatile Thread thread;

        @Override
        public CompletionStage<String> call() {
            thread = Thread.currentThread();
            calls.incrementAndGet();
            called.countDown();
            return stage;
        }

        boolean awaitCalled() throws InterruptedException {
            return called.await(5, TimeUnit.SECONDS);
        }

        /** Gives a wrongly started call time to show up. */
        boolean staysUncalled() throws InterruptedException {
            return !called.await(100, TimeUnit.MILLISECONDS);
        }
    }

    @Test
    void aFullBulkheadQueuesWithoutBlockingAndHandsTheSlotToTheOldestWaiterOnANewVirtualThread() throws Exception {
        BulkheadPolicy bulkhead = bulkhead(1, Duration.ofHours(1));
        assertThat(bulkhead.tryEnter()).isTrue();
        Probe first = new Probe();
        Probe second = new Probe();

        long start = System.nanoTime();
        CompletableFuture<String> firstResult = bulkhead.executeAsync(first);
        CompletableFuture<String> secondResult = bulkhead.executeAsync(second);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).as("did not block").isLessThan(Duration.ofSeconds(1));
        assertThat(firstResult).isNotDone();
        assertThat(secondResult).isNotDone();
        assertThat(bulkhead.queuedCalls()).isEqualTo(2);
        assertThat(bulkhead.waitingCalls()).isEqualTo(2);

        bulkhead.exit(); // a synchronous call frees its slot
        assertThat(first.awaitCalled()).isTrue();
        assertThat(first.thread.isVirtual()).isTrue();
        assertThat(first.thread).isNotSameAs(Thread.currentThread());
        assertThat(second.staysUncalled()).isTrue();
        assertThat(bulkhead.concurrentCalls()).isEqualTo(1);
        assertThat(bulkhead.queuedCalls()).isEqualTo(1);
        assertThat(bulkhead.waitingCalls()).isEqualTo(1);

        first.stage.complete("one"); // an asynchronous call frees its slot
        assertThat(firstResult.get(5, TimeUnit.SECONDS)).isEqualTo("one");
        assertThat(second.awaitCalled()).isTrue();
        assertThat(second.thread).isNotSameAs(Thread.currentThread());
        second.stage.complete("two");
        assertThat(secondResult.get(5, TimeUnit.SECONDS)).isEqualTo("two");
        assertEventuallyIdle(bulkhead);
        assertThat(bulkhead.queuedCalls()).isZero();
        assertThat(bulkhead.waitingCalls()).isZero();
    }

    @Test
    void aWaiterNotServedWithinMaxWaitIsRejectedAndNeverGetsTheSlot() throws Exception {
        BulkheadPolicy bulkhead = bulkhead(1, Duration.ofMillis(50));
        assertThat(bulkhead.tryEnter()).isTrue();
        Probe probe = new Probe();
        CompletableFuture<String> result = bulkhead.executeAsync(probe);
        assertThat(result.handle((value, failure) -> failure).get(5, TimeUnit.SECONDS))
                .isInstanceOf(BulkheadFullException.class).isSameAs(bulkhead.rejection());
        assertThat(bulkhead.queuedCalls()).isZero();

        bulkhead.exit();
        assertThat(probe.staysUncalled()).isTrue();
        assertEventuallyIdle(bulkhead);
    }

    @Test
    void aTimeoutThatWinsTheRaceKeepsTheSlotFromTheWaiterAndPassesItOn() throws Exception {
        BulkheadPolicy bulkhead = bulkhead(1, Duration.ofHours(1));
        assertThat(bulkhead.tryEnter()).isTrue();
        Probe late = new Probe();
        Probe next = new Probe();
        BulkheadWaiters.Waiter<String> timedOut = bulkhead.enqueueForTest(late);
        bulkhead.enqueueForTest(next);

        assertThat(timedOut.claim()).as("the timeout wins the waiter's state, but has not left the queue yet").isTrue();
        bulkhead.exit();
        assertThat(next.awaitCalled()).as("the slot goes to the next live waiter").isTrue();
        assertThat(late.staysUncalled()).isTrue();
        assertThat(bulkhead.concurrentCalls()).isEqualTo(1);

        timedOut.run(); // the rest of the timeout: already claimed, so it does nothing more
        assertThat(timedOut.result).isNotDone();
        next.stage.complete("x");
        assertEventuallyIdle(bulkhead);
    }

    @Test
    void aSlotThatNoLiveWaiterCanTakeGoesBackToTheSemaphore() throws Exception {
        BulkheadPolicy bulkhead = bulkhead(1, Duration.ofHours(1));
        assertThat(bulkhead.tryEnter()).isTrue();
        Probe late = new Probe();
        Probe cancelled = new Probe();
        BulkheadWaiters.Waiter<String> timedOut = bulkhead.enqueueForTest(late);
        bulkhead.enqueueForTest(cancelled).result.cancel(false);
        assertThat(timedOut.claim()).isTrue(); // timed out, still queued

        bulkhead.exit(); // one waiter still counted: drains, but neither waiter can take the slot
        assertThat(late.staysUncalled()).isTrue();
        assertThat(cancelled.calls).hasValue(0);
        assertThat(bulkhead.queuedCalls()).isZero();
        assertThat(bulkhead.concurrentCalls()).as("permit returned").isZero();
        assertThat(bulkhead.tryEnter()).isTrue();
    }

    @Test
    void aTimeoutThatLosesTheRaceLeavesTheWaiterRunning() throws Exception {
        BulkheadPolicy bulkhead = bulkhead(1, Duration.ofHours(1));
        assertThat(bulkhead.tryEnter()).isTrue();
        Probe probe = new Probe();
        BulkheadWaiters.Waiter<String> waiter = bulkhead.enqueueForTest(probe);

        bulkhead.exit(); // the hand-off claims the waiter first
        assertThat(probe.awaitCalled()).isTrue();
        waiter.run(); // the timer fires late
        assertThat(waiter.result).as("not failed by the late timeout").isNotDone();
        probe.stage.complete("served");
        assertThat(waiter.result.get(5, TimeUnit.SECONDS)).isEqualTo("served");
        assertEventuallyIdle(bulkhead);
    }

    @Test
    void aWaiterQueuedJustBeforeAReleaseIsServedByTheRelease() throws Exception {
        BulkheadPolicy bulkhead = bulkhead(1, Duration.ofHours(1));
        assertThat(bulkhead.tryEnter()).isTrue();
        Probe probe = new Probe();
        bulkhead.enqueueForTest(probe); // counted and queued, but its own drain has not run yet

        bulkhead.exit();
        assertThat(probe.awaitCalled()).isTrue();
        bulkhead.drainForTest(); // the waiter's own drain, late: finds nothing more to do
        assertThat(bulkhead.concurrentCalls()).isEqualTo(1);
        probe.stage.complete("x");
        assertEventuallyIdle(bulkhead);
    }

    @Test
    void aSlotReleasedJustBeforeAWaiterIsQueuedIsTakenByTheWaitersDrainInOrder() throws Exception {
        BulkheadPolicy bulkhead = bulkhead(1, Duration.ofHours(1));
        Probe older = new Probe();
        bulkhead.enqueueForTest(older); // queued while the bulkhead was full; the release came before its drain
        Probe newer = new Probe();

        CompletableFuture<String> newerResult = bulkhead.executeAsync(newer);
        assertThat(older.awaitCalled()).as("the free slot goes to the oldest waiter").isTrue();
        assertThat(newer.staysUncalled()).isTrue();
        assertThat(newerResult).isNotDone();
        older.stage.complete("x");
        assertThat(newer.awaitCalled()).isTrue();
        newer.stage.complete("y");
        assertThat(newerResult.get(5, TimeUnit.SECONDS)).isEqualTo("y");
    }

    @Test
    void aDrainThatLosesThePermitHandsNothingOffAndTheWaiterStaysQueued() throws Exception {
        BulkheadPolicy bulkhead = bulkhead(1, Duration.ofHours(1));
        assertThat(bulkhead.tryEnter()).isTrue();
        Probe probe = new Probe();
        bulkhead.enqueueForTest(probe);

        // As if the permit released by exit() had been taken by a blocked synchronous caller first.
        bulkhead.drainForTest();
        assertThat(probe.staysUncalled()).isTrue();
        assertThat(bulkhead.queuedCalls()).isEqualTo(1);
        assertThat(bulkhead.concurrentCalls()).isEqualTo(1);

        bulkhead.exit(); // that caller's own release serves the waiter
        assertThat(probe.awaitCalled()).isTrue();
        probe.stage.complete("x");
        assertEventuallyIdle(bulkhead);
    }

    @Test
    void aCancelledWaiterIsSkipped() throws Exception {
        BulkheadPolicy bulkhead = bulkhead(1, Duration.ofHours(1));
        assertThat(bulkhead.tryEnter()).isTrue();
        Probe cancelled = new Probe();
        Probe live = new Probe();
        bulkhead.executeAsync(cancelled).cancel(false);
        CompletableFuture<String> liveResult = bulkhead.executeAsync(live);

        bulkhead.exit();
        assertThat(live.awaitCalled()).isTrue();
        assertThat(cancelled.calls).hasValue(0);
        live.stage.complete("x");
        assertThat(liveResult.get(5, TimeUnit.SECONDS)).isEqualTo("x");
        assertThat(bulkhead.queuedCalls()).isZero();
        assertEventuallyIdle(bulkhead);
    }

    /**
     * The alternation is a heuristic: a release also wakes the blocked synchronous caller, which can win
     * the permit even on an asynchronous turn. Either way, the synchronous caller must be served within
     * the first two releases instead of after every asynchronous waiter.
     */
    @Test
    void releasesAlternateBetweenAsynchronousAndBlockedSynchronousWaiters() throws Exception {
        BulkheadPolicy bulkhead = bulkhead(1, Duration.ofSeconds(30));
        assertThat(bulkhead.tryEnter()).isTrue();
        List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
        Probe first = new Probe();
        Probe second = new Probe();
        bulkhead.executeAsync(() -> {
            order.add("first");
            return first.call();
        });
        bulkhead.executeAsync(() -> {
            order.add("second");
            return second.call();
        });
        CountDownLatch syncRelease = new CountDownLatch(1);
        Thread sync = Thread.ofPlatform().start(() -> {
            if (bulkhead.tryEnter()) {
                order.add("sync");
                try {
                    syncRelease.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    bulkhead.exit();
                }
            }
        });
        while (sync.getState() != Thread.State.TIMED_WAITING) {
            Thread.onSpinWait();
        }

        bulkhead.exit();
        for (int served = 1; served <= 3; served++) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (order.size() < served && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(order).hasSize(served);
            assertThat(second.staysUncalled() || served == 3).as("one call at a time: " + order).isTrue();
            switch (order.get(served - 1)) {
                case "sync" -> syncRelease.countDown();
                case "first" -> first.stage.complete("x");
                default -> second.stage.complete("y");
            }
        }
        sync.join(5_000);
        assertThat(order).containsExactlyInAnyOrder("first", "second", "sync");
        assertThat(order.indexOf("first")).isLessThan(order.indexOf("second"));
        assertThat(order.indexOf("sync")).as("served within two releases: " + order).isLessThanOrEqualTo(1);
        assertEventuallyIdle(bulkhead);
    }

    @Test
    void slotsAreConservedUnderAMixOfSynchronousAndAsynchronousCallers() throws Exception {
        int limit = 3;
        BulkheadPolicy bulkhead = bulkhead(limit, Duration.ofMillis(2));
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxSeen = new AtomicInteger();
        AtomicInteger served = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ExecutorService completer = Executors.newFixedThreadPool(2);
        List<CompletableFuture<String>> futures = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int t = 0; t < 4; t++) {
                threads.add(Thread.ofPlatform().start(() -> {
                    awaitQuietly(start);
                    for (int i = 0; i < 3_000; i++) {
                        if (bulkhead.tryEnter()) {
                            try {
                                served.incrementAndGet();
                                maxSeen.accumulateAndGet(running.incrementAndGet(), Math::max);
                                Thread.onSpinWait();
                                running.decrementAndGet();
                            } finally {
                                bulkhead.exit();
                            }
                        } else {
                            rejected.incrementAndGet();
                        }
                    }
                }));
            }
            for (int t = 0; t < 4; t++) {
                List<CompletableFuture<String>> mine = new ArrayList<>();
                threads.add(Thread.ofPlatform().start(() -> {
                    awaitQuietly(start);
                    for (int i = 0; i < 3_000; i++) {
                        int kind = ThreadLocalRandom.current().nextInt(3);
                        mine.add(bulkhead.executeAsync(() -> {
                            maxSeen.accumulateAndGet(running.incrementAndGet(), Math::max);
                            served.incrementAndGet();
                            if (kind == 0) {
                                running.decrementAndGet();
                                return CompletableFuture.completedFuture("now");
                            }
                            if (kind == 1) {
                                running.decrementAndGet();
                                throw new IllegalStateException("thrown");
                            }
                            return CompletableFuture.supplyAsync(() -> {
                                running.decrementAndGet();
                                return "later";
                            }, completer);
                        }));
                    }
                    synchronized (futures) {
                        futures.addAll(mine);
                    }
                }));
            }
            start.countDown();
            for (Thread thread : threads) {
                thread.join();
            }
            for (CompletableFuture<String> future : futures) {
                Throwable failure = future.handle((value, f) -> f).get(10, TimeUnit.SECONDS);
                if (failure instanceof BulkheadFullException) {
                    rejected.incrementAndGet();
                }
            }
        } finally {
            completer.shutdown();
        }
        assertThat(futures).hasSize(12_000);
        assertThat(maxSeen.get()).isBetween(1, limit);
        assertThat(served.get()).isPositive();
        assertEventuallyIdle(bulkhead); // no slot lost or given twice
        assertThat(bulkhead.queuedCalls()).isZero();
        for (int i = 0; i < limit; i++) {
            assertThat(bulkhead.tryEnter()).isTrue();
        }
        assertThat(bulkhead.tryEnter()).isFalse();
    }

    /** The slot of a granted call is freed on whichever thread completes its stage, possibly just after the test's assertion. */
    private static void assertEventuallyIdle(BulkheadPolicy bulkhead) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (bulkhead.concurrentCalls() != 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(bulkhead.concurrentCalls()).isZero();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void withoutMaxWaitAFullBulkheadStillRejectsAtOnce() {
        BulkheadPolicy bulkhead = bulkhead(1, Duration.ZERO);
        assertThat(bulkhead.tryEnter()).isTrue();
        assertThat(failureOf(bulkhead.executeAsync(() -> CompletableFuture.completedFuture("x"))))
                .isSameAs(bulkhead.rejection());
        assertThat(bulkhead.queuedCalls()).isZero();
    }
}
