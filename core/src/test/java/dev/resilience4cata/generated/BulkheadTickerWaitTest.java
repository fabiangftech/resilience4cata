package dev.resilience4cata.generated;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.BulkheadConfig;
import dev.resilience4cata.event.BulkheadEvent;
import dev.resilience4cata.event.EventListener;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The outcomes of a synchronous wait measured on a manual ticker ({@link BulkheadPolicy.TickerWait}),
 * replayed deterministically on the calling thread: the deadline's {@code run()} and the waiting
 * thread's {@code afterTickerWait} in every order. End-to-end behaviour on a {@code ManualClock} is
 * tested in the testkit module.
 */
class BulkheadTickerWaitTest {

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    private static BulkheadPolicy bulkhead(int maxConcurrent) {
        return new BulkheadPolicy("test",
                BulkheadConfig.builder().maxConcurrent(maxConcurrent).maxWait(Duration.ofHours(1)).build());
    }

    @Test
    void acquiredBeforeTheDeadlineKeepsThePermitAndTheDeadlineThenDoesNothing() {
        BulkheadPolicy bulkhead = bulkhead(2);
        BulkheadPolicy.TickerWait wait = bulkhead.tickerWaitForTest(Thread.currentThread());

        assertThat(bulkhead.tryAcquireForTest()).isTrue();
        assertThat(bulkhead.afterTickerWait(wait, null, true)).isTrue();
        wait.run(); // fires late: must neither take a permit nor interrupt

        assertThat(bulkhead.concurrentCalls()).isEqualTo(1);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void anInterruptBeforeTheDeadlineIsARejectionWithTheFlagSetAgain() {
        BulkheadPolicy bulkhead = bulkhead(1);
        BulkheadPolicy.TickerWait wait = bulkhead.tickerWaitForTest(Thread.currentThread());
        CompletableFuture<Void> deadline = new CompletableFuture<>();

        assertThat(bulkhead.afterTickerWait(wait, deadline, false)).isFalse();

        assertThat(Thread.interrupted()).as("the caller's interrupt is kept").isTrue();
        assertThat(deadline).as("the deadline is cancelled").isCancelled();
        wait.run();
        assertThat(bulkhead.concurrentCalls()).isZero();
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void aDeadlineThatFindsAFreePermitGrantsItToTheWaiter() {
        BulkheadPolicy bulkhead = bulkhead(1);
        BulkheadPolicy.TickerWait wait = bulkhead.tickerWaitForTest(Thread.currentThread());

        wait.run(); // the slot was released, but the waiter had not woken up yet
        assertThat(bulkhead.concurrentCalls()).isEqualTo(1);
        assertThat(bulkhead.afterTickerWait(wait, null, false)).as("woken by the deadline's interrupt").isTrue();

        assertThat(Thread.currentThread().isInterrupted()).as("the deadline's interrupt is cleared").isFalse();
        assertThat(bulkhead.concurrentCalls()).isEqualTo(1);
    }

    @Test
    void aGrantRacingWithAnAcquireReleasesTheExtraPermit() {
        BulkheadPolicy bulkhead = bulkhead(2);
        BulkheadPolicy.TickerWait wait = bulkhead.tickerWaitForTest(Thread.currentThread());

        assertThat(bulkhead.tryAcquireForTest()).isTrue(); // acquire() returned...
        wait.run(); // ...while the deadline took the other free permit
        assertThat(bulkhead.concurrentCalls()).isEqualTo(2);

        assertThat(bulkhead.afterTickerWait(wait, null, true)).isTrue();
        assertThat(bulkhead.concurrentCalls()).as("one call, one permit").isEqualTo(1);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void aDeadlineThatFindsNoPermitRejects() {
        BulkheadPolicy bulkhead = bulkhead(1);
        assertThat(bulkhead.tryAcquireForTest()).isTrue(); // held by another call
        BulkheadPolicy.TickerWait wait = bulkhead.tickerWaitForTest(Thread.currentThread());

        wait.run();
        assertThat(bulkhead.afterTickerWait(wait, null, false)).isFalse();

        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        assertThat(bulkhead.concurrentCalls()).isEqualTo(1);
    }

    @Test
    void aPermitAcquiredAsTheDeadlineGivesUpIsKept() {
        BulkheadPolicy bulkhead = bulkhead(1);
        assertThat(bulkhead.tryAcquireForTest()).isTrue();
        BulkheadPolicy.TickerWait wait = bulkhead.tickerWaitForTest(Thread.currentThread());

        bulkhead.exit(); // the holder leaves just before the deadline...
        assertThat(bulkhead.tryAcquireForTest()).isTrue(); // ...and the semaphore gives the slot to the waiter
        wait.run(); // the deadline finds no free permit: it gives up, but too late

        assertThat(bulkhead.afterTickerWait(wait, null, true)).isTrue();
        assertThat(bulkhead.concurrentCalls()).isEqualTo(1);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void aReleasedPermitGoesToAQueuedAsyncCallerOrToTheDeadlineNeverBoth() {
        BulkheadPolicy bulkhead = bulkhead(1);
        assertThat(bulkhead.tryAcquireForTest()).isTrue();
        BulkheadWaiters.Waiter<String> queued = bulkhead.enqueueForTest(CompletableFuture::new);
        BulkheadPolicy.TickerWait wait = bulkhead.tickerWaitForTest(Thread.currentThread());

        bulkhead.exit(); // handed off to the asynchronous waiter
        wait.run();

        assertThat(queued.isClaimed()).isTrue();
        assertThat(bulkhead.afterTickerWait(wait, null, false)).isFalse();
        assertThat(bulkhead.concurrentCalls()).isEqualTo(1);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void onlyTheWaitingThreadRejectsAndAKeptPermitPublishesNoRejection() {
        List<Thread> rejectedOn = new CopyOnWriteArrayList<>();
        BulkheadPolicy bulkhead = new BulkheadPolicy("test",
                BulkheadConfig.builder().maxConcurrent(1).maxWait(Duration.ofHours(1)).build(),
                PolicyEnvironment.forTest(() -> 0L, nanos -> {}, EventDispatcher.of(List.of(new EventDispatcher.Subscription(
                        BulkheadEvent.Rejected.class, null,
                        (EventListener<BulkheadEvent.Rejected>) e -> rejectedOn.add(Thread.currentThread()))))));
        assertThat(bulkhead.tryAcquireForTest()).isTrue();

        BulkheadPolicy.TickerWait kept = bulkhead.tickerWaitForTest(Thread.currentThread());
        bulkhead.exit();
        assertThat(bulkhead.tryAcquireForTest()).isTrue(); // acquire() got the slot released just before...
        kept.run(); // ...the deadline, which gives up and interrupts
        assertThat(rejectedOn).as("the deadline publishes nothing").isEmpty();
        assertThat(bulkhead.afterTickerWait(kept, null, true)).isTrue();
        assertThat(Thread.currentThread().isInterrupted()).as("cleared before the call runs").isFalse();
        assertThat(rejectedOn).isEmpty();

        BulkheadPolicy.TickerWait timedOut = bulkhead.tickerWaitForTest(Thread.currentThread());
        timedOut.run();
        assertThat(rejectedOn).isEmpty();
        assertThat(bulkhead.afterTickerWait(timedOut, null, false)).isFalse();
        assertThat(rejectedOn).containsExactly(Thread.currentThread());
    }
}
