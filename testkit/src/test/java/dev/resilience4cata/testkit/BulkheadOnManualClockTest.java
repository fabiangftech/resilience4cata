package dev.resilience4cata.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.BulkheadFullException;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.generated.BulkheadPolicy;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A synchronous bulkhead with {@code maxWait > 0} on a {@link ManualClock}: blocked callers wait for the
 * clock, not for real time. Each blocked caller runs on its own thread; the test waits (in real time,
 * bounded) only for that thread to block or to return, never for the wait limit.
 */
class BulkheadOnManualClockTest {

    private static final long REAL_TIME_LIMIT = TimeUnit.SECONDS.toNanos(10);

    private final ManualClock clock = new ManualClock();
    private final ResilienceRegistry registry = ResilienceTesting.registry(clock)
            .bulkheadDefaults(b -> b.maxConcurrent(1).maxWait(Duration.ofSeconds(3)))
            .build();
    private final BulkheadPolicy bulkhead = registry.bulkheadPolicy("b", "", "", null);

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void aBlockedCallerIsRejectedExactlyWhenTheClockPassesMaxWait() throws Exception {
        long started = System.nanoTime();
        assertThat(bulkhead.tryEnter()).isTrue();
        Blocked blocked = Blocked.start(bulkhead);
        awaitBlocked(blocked);

        clock.advance(Duration.ofMillis(2_999));
        assertThat(blocked.isWaiting()).isTrue();
        assertThat(bulkhead.waitingCalls()).isEqualTo(1);

        clock.advance(Duration.ofMillis(1));
        assertThat(blocked.result()).isFalse();
        assertThat(System.nanoTime() - started).as("not in real time").isLessThan(TimeUnit.SECONDS.toNanos(2));
        assertThat(blocked.interruptedAfterwards).as("the deadline's interrupt is cleared").isFalse();
        assertThat(bulkhead.waitingCalls()).isZero();
        assertThat(bulkhead.concurrentCalls()).isEqualTo(1);
        bulkhead.exit();
        assertThat(bulkhead.concurrentCalls()).isZero();
    }

    @Test
    void aBlockedCallerGetsASlotReleasedBeforeTheDeadlineAtOnce() throws Exception {
        assertThat(bulkhead.tryEnter()).isTrue();
        Blocked blocked = Blocked.start(bulkhead);
        awaitBlocked(blocked);
        clock.advance(Duration.ofSeconds(2));

        bulkhead.exit();

        assertThat(blocked.result()).isTrue();
        assertThat(clock.elapsed()).isEqualTo(Duration.ofSeconds(2));
        assertThat(clock.pendingTasks()).as("the deadline is cancelled").isZero();
        assertThat(bulkhead.concurrentCalls()).isEqualTo(1);
        bulkhead.exit();
        assertThat(bulkhead.concurrentCalls()).isZero();
    }

    @Test
    void aSlotReleasedJustBeforeTheDeadlineIsNeverMissed() throws Exception {
        for (int i = 0; i < 50; i++) {
            assertThat(bulkhead.tryEnter()).isTrue();
            Blocked blocked = Blocked.start(bulkhead);
            awaitBlocked(blocked);

            bulkhead.exit();
            clock.advance(Duration.ofSeconds(3)); // the waiter may not have woken up yet

            assertThat(blocked.result()).isTrue();
            assertThat(blocked.interruptedAfterwards).isFalse();
            assertThat(bulkhead.concurrentCalls()).isEqualTo(1);
            bulkhead.exit();
            assertThat(bulkhead.concurrentCalls()).isZero();
        }
    }

    @Test
    void aSlowCallSleepingOnTheClockMakesTheWaiterGiveUp() throws Exception {
        assertThat(bulkhead.tryEnter()).isTrue();
        Blocked blocked = Blocked.start(bulkhead);
        awaitBlocked(blocked);

        clock.sleep(Duration.ofSeconds(5)); // what a stub simulating a slow dependency does, holding the slot

        assertThat(blocked.result()).isFalse();
        bulkhead.exit();
        assertThat(bulkhead.concurrentCalls()).isZero();
    }

    @Test
    void anInterruptIsStillARejectionWithTheFlagSetAgain() throws Exception {
        assertThat(bulkhead.tryEnter()).isTrue();
        Blocked blocked = Blocked.start(bulkhead);
        awaitBlocked(blocked);

        blocked.thread.interrupt();

        assertThat(blocked.result()).isFalse();
        assertThat(blocked.interruptedAfterwards).isTrue();
        assertThat(clock.pendingTasks()).isZero();
        bulkhead.exit();
        assertThat(bulkhead.concurrentCalls()).isZero();
    }

    @Test
    void synchronousAndAsynchronousWaitersShareReleasedSlotsAndBothFollowTheClock() throws Exception {
        assertThat(bulkhead.tryEnter()).isTrue();
        Blocked blocked = Blocked.start(bulkhead);
        awaitBlocked(blocked);
        CompletableFuture<String> busy = new CompletableFuture<>();
        CompletableFuture<String> queued = bulkhead.executeAsync(() -> busy);
        assertThat(bulkhead.waitingCalls()).isEqualTo(2);

        bulkhead.exit(); // to the asynchronous waiter, or to the synchronous one if it wins the semaphore
        awaitTrue(() -> bulkhead.waitingCalls() == 1 && (blocked.entered.isDone() || blocked.isWaiting()));
        boolean syncWon = blocked.entered.isDone();
        assertThat(bulkhead.concurrentCalls()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(3)); // both waits began at 0: the one still waiting gives up
        if (syncWon) {
            assertThat(blocked.result()).isTrue();
            assertThat(queued).isCompletedExceptionally();
            bulkhead.exit();
        } else {
            assertThat(blocked.result()).isFalse();
            assertThat(queued).isNotDone(); // it holds the slot: its deadline no longer applies
            busy.complete("done");
            assertThat(queued.join()).isEqualTo("done");
        }
        assertThat(blocked.interruptedAfterwards).isFalse();
        awaitTrue(() -> bulkhead.concurrentCalls() == 0);
        assertThat(bulkhead.waitingCalls()).isZero();
    }

    @Test
    void anAsynchronousWaiterTimesOutOnTheClockWhileASynchronousOneGetsTheSlot() throws Exception {
        assertThat(bulkhead.tryEnter()).isTrue();
        CompletableFuture<String> queued = bulkhead.executeAsync(() -> CompletableFuture.completedFuture("ran"));
        clock.advance(Duration.ofSeconds(1));
        Blocked blocked = Blocked.start(bulkhead);
        awaitBlocked(blocked);
        assertThat(bulkhead.waitingCalls()).isEqualTo(2);

        clock.advance(Duration.ofSeconds(2)); // the asynchronous waiter's deadline, not the synchronous one's
        assertThat(queued).isCompletedExceptionally();
        assertThat(queued.handle((v, e) -> e).join()).isInstanceOf(BulkheadFullException.class);
        assertThat(blocked.isWaiting()).isTrue();

        bulkhead.exit();
        assertThat(blocked.result()).isTrue();
        bulkhead.exit();
        assertThat(bulkhead.concurrentCalls()).isZero();
    }

    /** Waits until the caller is parked on the semaphore, not just queued. */
    private static void awaitBlocked(Blocked blocked) {
        awaitTrue(blocked::isWaiting);
    }

    private static void awaitTrue(BooleanSupplier condition) {
        long deadline = System.nanoTime() + REAL_TIME_LIMIT;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) {
                throw new AssertionError("Condition not met in real time");
            }
            Thread.onSpinWait();
        }
    }

    /** A synchronous caller of {@code tryEnter()} on its own thread. */
    private static final class Blocked {
        final CompletableFuture<Boolean> entered = new CompletableFuture<>();
        final Thread thread;
        volatile boolean interruptedAfterwards;

        private Blocked(BulkheadPolicy bulkhead) {
            thread = Thread.ofPlatform().daemon().unstarted(() -> {
                boolean result = bulkhead.tryEnter();
                interruptedAfterwards = Thread.interrupted();
                entered.complete(result);
            });
        }

        static Blocked start(BulkheadPolicy bulkhead) {
            Blocked blocked = new Blocked(bulkhead);
            blocked.thread.start();
            return blocked;
        }

        boolean isWaiting() {
            return !entered.isDone() && thread.getState() == Thread.State.WAITING;
        }

        boolean result() throws Exception {
            return entered.get(10, TimeUnit.SECONDS);
        }
    }
}
