package dev.resilience4cata.generated;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import dev.resilience4cata.BulkheadConfig;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.III_Result;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * Downward cancellation ({@link LinkedFuture}) racing with what it cancels: the link a policy moves to
 * next, the stage's own completion, and a bulkhead's hand-off of a slot to a queued waiter. What must
 * hold: whatever was linked is cancelled exactly once, and a bulkhead slot is released exactly once. In
 * package {@code dev.resilience4cata.generated} for the package-private seams, like {@link TimeoutStress}.
 */
public final class CancellationStress {

    private CancellationStress() {}

    /** Counts how often it was cancelled. */
    static final class Hook implements LinkedFuture.OnCancel {
        final AtomicInteger cancels = new AtomicInteger();

        @Override
        public void cancelled() {
            cancels.incrementAndGet();
        }
    }

    /**
     * The policy moves its link from {@code first} to {@code second} (a retry starting its backoff, say)
     * while the caller cancels. Either the cancel took {@code first} and the link then found the mark
     * (both cancelled), or the link came first and the cancel took {@code second}; {@code second} is never
     * left running, and nothing is cancelled twice.
     */
    @JCStressTest
    @Outcome(id = "1, 1", expect = ACCEPTABLE, desc = "Cancel first: the old link and, at once, the new one.")
    @Outcome(id = "0, 1", expect = ACCEPTABLE, desc = "Link first: the cancel reached the new link only.")
    @Outcome(id = ".*, 0", expect = FORBIDDEN, desc = "The new link was never cancelled: it runs on.")
    @Outcome(expect = FORBIDDEN, desc = "Something was cancelled twice.")
    @State
    public static class CancelVsLink {
        final Hook first = new Hook();
        final Hook second = new Hook();
        final LinkedFuture<String> future = new LinkedFuture<>(first);

        @Actor
        public void cancel() {
            future.cancel(true);
        }

        @Actor
        public void link() {
            future.link(second);
        }

        @Arbiter
        public void count(II_Result r) {
            r.r1 = first.cancels.get();
            r.r2 = second.cancels.get();
        }
    }

    /**
     * A retry's backoff: the thread that saw the attempt fail links the timer in place of that attempt
     * ({@code replace}), while the timer's task, already fired, links the next attempt ({@code link}), and
     * the caller cancels. The next attempt must always be cancelled (it is the live one), exactly once; the
     * timer is cancelled at most once (not at all if the next attempt replaced it, since it already fired).
     */
    @JCStressTest
    @Outcome(id = {"0, 1", "1, 1"}, expect = ACCEPTABLE, desc = "The live attempt was cancelled once.")
    @Outcome(id = ".*, 0", expect = FORBIDDEN, desc = "The timer was linked over the next attempt, which runs on.")
    @Outcome(expect = FORBIDDEN, desc = "Something was cancelled twice.")
    @State
    public static class CancelVsBackoffLinks {
        final Object failedAttempt = new Object();
        final Hook timer = new Hook();
        final Hook nextAttempt = new Hook();
        final LinkedFuture<String> future = new LinkedFuture<>(failedAttempt);

        @Actor
        public void scheduleBackoff() {
            future.replace(failedAttempt, timer); // what retryAfter read: the attempt that failed
        }

        @Actor
        public void startNextAttempt() {
            future.link(nextAttempt);
        }

        @Actor
        public void cancel() {
            future.cancel(true);
        }

        @Arbiter
        public void count(II_Result r) {
            r.r1 = timer.cancels.get();
            r.r2 = nextAttempt.cancels.get();
        }
    }

    /** Two callers cancel at once: the link is cancelled once and the future ends cancelled. */
    @JCStressTest
    @Outcome(id = "1, 1", expect = ACCEPTABLE, desc = "Cancelled once.")
    @Outcome(expect = FORBIDDEN, desc = "Cancelled twice, or not at all.")
    @State
    public static class CancelVsCancel {
        final Hook hook = new Hook();
        final LinkedFuture<String> future = new LinkedFuture<>(hook);

        @Actor
        public void cancel1() {
            future.cancel(true);
        }

        @Actor
        public void cancel2() {
            future.cancel(true);
        }

        @Arbiter
        public void count(II_Result r) {
            r.r1 = hook.cancels.get();
            r.r2 = future.isCancelled() ? 1 : 0;
        }
    }

    /**
     * A call holds the only slot of a bulkhead without {@code maxWait}. The caller cancels its future
     * while its stage completes normally. Whichever wins, the slot is released exactly once: a second
     * release would show {@code -1}, a lost one {@code 1}.
     */
    @JCStressTest
    @Outcome(id = "0, 1", expect = ACCEPTABLE, desc = "Cancel first: the future is cancelled, the slot free.")
    @Outcome(id = "0, 2", expect = ACCEPTABLE, desc = "Completion first: the future has the value, the slot free.")
    @Outcome(id = "-1, .*", expect = FORBIDDEN, desc = "The slot was released twice.")
    @Outcome(expect = FORBIDDEN, desc = "The slot leaked, or the future ended otherwise.")
    @State
    public static class CancelVsCompleteHeld {
        final BulkheadPolicy bulkhead = new BulkheadPolicy("stress",
                BulkheadConfig.builder().maxConcurrent(1).build());
        final CompletableFuture<String> stage = new CompletableFuture<>();
        final CompletableFuture<String> result = bulkhead.executeAsync(() -> stage);

        @Actor
        public void cancel() {
            result.cancel(true);
        }

        @Actor
        public void complete() {
            stage.complete("ok");
        }

        @Arbiter
        public void count(II_Result r) {
            r.r1 = bulkhead.concurrentCalls();
            r.r2 = result.isCancelled() ? 1 : "ok".equals(result.getNow(null)) ? 2 : 0;
        }
    }

    /**
     * The same with {@code maxWait}, where slots are semaphore permits: a double release would add a
     * permit for good.
     */
    @JCStressTest
    @Outcome(id = "0, 1", expect = ACCEPTABLE, desc = "Cancel first: the future is cancelled, the slot free.")
    @Outcome(id = "0, 2", expect = ACCEPTABLE, desc = "Completion first: the future has the value, the slot free.")
    @Outcome(id = "-1, .*", expect = FORBIDDEN, desc = "The permit was released twice.")
    @Outcome(expect = FORBIDDEN, desc = "The permit leaked, or the future ended otherwise.")
    @State
    public static class CancelVsCompleteHeldWaiting {
        final BulkheadPolicy bulkhead = new BulkheadPolicy("stress",
                BulkheadConfig.builder().maxConcurrent(1).maxWait(Duration.ofHours(1)).build());
        final CompletableFuture<String> stage = new CompletableFuture<>();
        final CompletableFuture<String> result = bulkhead.executeAsync(() -> stage);

        @Actor
        public void cancel() {
            result.cancel(true);
        }

        @Actor
        public void complete() {
            stage.complete("ok");
        }

        @Arbiter
        public void count(II_Result r) {
            r.r1 = bulkhead.concurrentCalls();
            r.r2 = result.isCancelled() ? 1 : "ok".equals(result.getNow(null)) ? 2 : 0;
        }
    }

    /**
     * A queued waiter is cancelled while a release hands it the free slot: the hand-off takes the
     * permit and claims the waiter, then runs its call (what {@code grant} does on a new virtual thread,
     * here on the actor's thread); if the claim fails it gives the permit back, as {@code drain} does.
     * Whatever the order, the waiter leaves the queue, the slot ends free, and a call that started was
     * cancelled ({@code r2} is the number of calls started).
     */
    @JCStressTest
    @Outcome(id = "0, 0, 0", expect = ACCEPTABLE, desc = "The cancel claimed the waiter, or the granted run saw the cancel.")
    @Outcome(id = "0, 1, 0", expect = ACCEPTABLE, desc = "The call started, then the cancel stopped it and freed the slot.")
    @Outcome(id = "1, .*", expect = FORBIDDEN, desc = "The slot leaked.")
    @Outcome(id = "-1, .*", expect = FORBIDDEN, desc = "The slot was released twice.")
    @Outcome(expect = FORBIDDEN, desc = "The waiter stayed queued, or a started call ran on.")
    @State
    public static class CancelVsHandOff {
        final BulkheadPolicy bulkhead = new BulkheadPolicy("stress",
                BulkheadConfig.builder().maxConcurrent(1).maxWait(Duration.ofHours(1)).build());
        final AtomicInteger calls = new AtomicInteger();
        final CompletableFuture<String> stage = new CompletableFuture<>();
        final BulkheadWaiters.Waiter<String> waiter = bulkhead.enqueueForTest(() -> {
            calls.incrementAndGet();
            return stage;
        });

        @Actor
        public void cancel() {
            waiter.result.cancel(true);
        }

        @Actor
        public void handOff() {
            if (!bulkhead.tryAcquireForTest()) {
                return;
            }
            if (waiter.claim()) {
                bulkhead.runGrantedForTest(waiter);
            } else {
                bulkhead.exit();
            }
        }

        @Arbiter
        public void count(III_Result r) {
            r.r1 = bulkhead.concurrentCalls();
            int started = calls.get();
            r.r2 = started == 1 && !stage.isCancelled() ? 9 : started;
            r.r3 = bulkhead.queuedCalls();
        }
    }
}
