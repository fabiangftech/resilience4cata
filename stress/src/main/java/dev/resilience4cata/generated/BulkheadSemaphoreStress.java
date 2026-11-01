package dev.resilience4cata.generated;

import dev.resilience4cata.BulkheadConfig;
import java.time.Duration;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Mode;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.Signal;
import org.openjdk.jcstress.annotations.State;

/**
 * {@link BulkheadSemaphore}: a release that races a thread on its way to park must wake it (the store-buffering
 * pair between the release's volatile add and the queued thread's re-check of the counter). The bulkhead is
 * full; one thread waits for a permit, and the signal gives one back at any moment of that wait, before the
 * thread queued, while it queues, or after it parked. A waiter left parked with a permit free is a lost wake-up.
 * In package {@code dev.resilience4cata.generated}, like {@link TimeoutStress}, because the semaphore is
 * package-private.
 */
public final class BulkheadSemaphoreStress {

    private BulkheadSemaphoreStress() {}

    /** The semaphore alone: {@code acquire()} against {@code release()}. */
    @JCStressTest(Mode.Termination)
    @Outcome(id = "TERMINATED", expect = Expect.ACCEPTABLE, desc = "the waiter took the released permit")
    @Outcome(id = "STALE", expect = Expect.FORBIDDEN, desc = "lost wake-up: the waiter sleeps while a permit is free")
    @State
    public static class ReleaseWakesAWaiter {
        final BulkheadSemaphore semaphore = new BulkheadSemaphore(1);

        public ReleaseWakesAWaiter() {
            if (!semaphore.tryAcquire()) {
                throw new IllegalStateException("could not fill the semaphore");
            }
        }

        @Actor
        public void waiter() {
            try {
                semaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // jcstress interrupts a stale actor
            }
        }

        @Signal
        public void release() {
            semaphore.release();
        }
    }

    /** Through the policy: a synchronous {@code tryEnter} waiting up to an hour against {@code exit()}. */
    @JCStressTest(Mode.Termination)
    @Outcome(id = "TERMINATED", expect = Expect.ACCEPTABLE, desc = "the waiting call entered in the freed slot")
    @Outcome(id = "STALE", expect = Expect.FORBIDDEN, desc = "lost wake-up: the call waits while a slot is free")
    @State
    public static class ExitWakesAWaitingCall {
        final BulkheadPolicy bulkhead = new BulkheadPolicy("stress",
                BulkheadConfig.builder().maxConcurrent(1).maxWait(Duration.ofHours(1)).build());

        public ExitWakesAWaitingCall() {
            if (!bulkhead.tryEnter()) {
                throw new IllegalStateException("could not fill the bulkhead");
            }
        }

        @Actor
        public void waiter() {
            if (!bulkhead.tryEnter()) {
                throw new IllegalStateException("rejected before the wait limit");
            }
        }

        @Signal
        public void exit() {
            bulkhead.exit();
        }
    }
}
