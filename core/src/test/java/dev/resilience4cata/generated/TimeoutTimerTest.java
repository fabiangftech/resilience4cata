package dev.resilience4cata.generated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.TimeoutConfig;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TimeoutTimerTest {

    private static final long MS = 1_000_000L;

    private final List<Runnable> launched = new ArrayList<>();
    private final TimeoutTimer timer = new TimeoutTimer(launched::add);

    private static final class Counting extends TimerEntry {
        final AtomicInteger runs = new AtomicInteger();

        @Override
        public void run() {
            runs.incrementAndGet();
        }
    }

    private void runLaunched() {
        List<Runnable> tasks = new ArrayList<>(launched);
        launched.clear();
        tasks.forEach(Runnable::run);
    }

    @Test
    void anEntryFiresOnceItsDurationHasPassedSinceTheTimerTookIt() {
        TimeoutTimer.Lane lane = timer.lane(100 * MS);
        Counting entry = new Counting();
        timer.schedule(entry, lane);
        assertThat(timer.pending()).isEqualTo(1);

        timer.tick(50 * MS); // taken now: the deadline is 150 ms, never earlier than the call's start + 100 ms
        timer.tick(150 * MS - 1);
        assertThat(launched).isEmpty();

        timer.tick(150 * MS);
        assertThat(launched).containsExactly(entry);
        runLaunched();
        assertThat(entry.runs).hasValue(1);
        assertThat(entry.state()).isEqualTo(TimerEntry.FIRED);
        assertThat(entry.cancel()).as("too late").isFalse();
        assertThat(timer.pending()).isZero();

        timer.tick(Duration.ofHours(1).toNanos());
        assertThat(launched).as("fires once").isEmpty();
    }

    @Test
    void anEntryCancelledBeforeTheTimerSawItIsDroppedAndNeverFires() {
        // Also the race between the timer taking a NEW entry and a cancel: whichever CAS wins decides.
        TimeoutTimer.Lane lane = timer.lane(100 * MS);
        Counting entry = new Counting();
        timer.schedule(entry, lane);
        assertThat(entry.cancel()).isTrue();
        assertThat(entry.cancel()).as("only once").isFalse();

        timer.tick(0);
        assertThat(timer.pending()).isZero();
        timer.tick(Duration.ofHours(1).toNanos());
        assertThat(launched).isEmpty();
    }

    @Test
    void anEntryCancelledAfterTheTimerScheduledItIsUnlinkedAtTheNextTick() {
        TimeoutTimer.Lane lane = timer.lane(Duration.ofHours(1).toNanos());
        Counting entry = new Counting();
        timer.schedule(entry, lane);
        timer.tick(0);
        assertThat(entry.state()).isEqualTo(TimerEntry.SCHEDULED);
        assertThat(timer.pending()).isEqualTo(1);

        assertThat(entry.cancel()).isTrue();
        timer.tick(MS);
        assertThat(timer.pending()).as("memory released long before the hour").isZero();
        assertThat(entry.linked).isFalse();

        timer.tick(Duration.ofHours(2).toNanos());
        assertThat(launched).isEmpty();
    }

    @Test
    void aCancelThatLandsBetweenExpiryAndItsUnlinkLeavesTheLaneIntact() {
        // Replays: A is cancelled after the timer scheduled it, and the timer reaches A's deadline
        // before it processes the cancelled stack. A must not fire, and unlinking A a second time when
        // the cancelled stack is drained must not drop B, which follows A in the same lane.
        TimeoutTimer.Lane lane = timer.lane(100 * MS);
        Counting a = new Counting();
        Counting b = new Counting();
        timer.schedule(a, lane);
        timer.tick(0);
        timer.schedule(b, lane);
        timer.tick(10 * MS);

        assertThat(a.cancel()).isTrue();
        timer.expire(100 * MS); // without drainCancelled first
        assertThat(launched).isEmpty();
        timer.drainCancelled();
        assertThat(timer.pending()).isEqualTo(1);

        timer.tick(110 * MS);
        assertThat(launched).containsExactly(b);
    }

    @Test
    void aStaleSecondCancelAfterFiringChangesNothing() {
        TimeoutTimer.Lane lane = timer.lane(MS);
        Counting entry = new Counting();
        timer.schedule(entry, lane);
        timer.tick(0);
        timer.tick(MS);
        assertThat(entry.cancel()).isFalse();
        timer.tick(2 * MS);
        assertThat(launched).containsExactly(entry);
    }

    @Test
    void lanesExpireInDeadlineOrderAndIndependently() {
        TimeoutTimer.Lane shortLane = timer.lane(10 * MS);
        TimeoutTimer.Lane longLane = timer.lane(100 * MS);
        Counting long1 = new Counting();
        Counting short1 = new Counting();
        Counting short2 = new Counting();
        timer.schedule(long1, longLane);
        timer.schedule(short1, shortLane);
        timer.tick(0);
        timer.schedule(short2, shortLane);
        timer.tick(5 * MS);

        timer.tick(10 * MS);
        assertThat(launched).containsExactly(short1);
        timer.tick(15 * MS);
        assertThat(launched).containsExactly(short1, short2);
        timer.tick(100 * MS);
        assertThat(launched).containsExactly(short1, short2, long1);

        // An emptied lane can be used again.
        Counting again = new Counting();
        timer.schedule(again, shortLane);
        timer.tick(200 * MS);
        timer.tick(210 * MS);
        assertThat(launched).endsWith(again);
        assertThat(timer.pending()).isZero();
    }

    @Test
    void theTickFollowsTheShortestDuration() {
        TimeoutTimer fresh = new TimeoutTimer(task -> { });
        fresh.schedule(new Counting(), fresh.lane(Duration.ofSeconds(10).toNanos()));
        assertThat(fresh.tick()).isEqualTo(50 * MS);
        fresh.schedule(new Counting(), fresh.lane(160 * MS));
        assertThat(fresh.tick()).isEqualTo(10 * MS);
        fresh.schedule(new Counting(), fresh.lane(MS));
        assertThat(fresh.tick()).isEqualTo(MS);
    }

    @Test
    void aLaneBelongsToOneTimer() {
        TimeoutTimer other = new TimeoutTimer(task -> { });
        TimeoutTimer.Lane foreign = other.lane(MS);
        Counting entry = new Counting();
        assertThatThrownBy(() -> timer.schedule(entry, foreign))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentSchedulesAndCancelsAreAllAccountedFor() throws InterruptedException {
        ConcurrentLinkedQueue<Runnable> fired = new ConcurrentLinkedQueue<>();
        TimeoutTimer concurrent = new TimeoutTimer(fired::add);
        TimeoutTimer.Lane lane = concurrent.lane(3 * MS);
        int threads = 4;
        int perThread = 20_000;
        AtomicInteger cancelled = new AtomicInteger();
        AtomicBoolean done = new AtomicBoolean();
        CountDownLatch finished = new CountDownLatch(threads);
        Thread ticker = Thread.ofPlatform().start(() -> {
            for (long now = MS; !done.get(); now += MS) {
                concurrent.tick(now);
            }
        });
        for (int t = 0; t < threads; t++) {
            Thread.ofPlatform().start(() -> {
                for (int i = 0; i < perThread; i++) {
                    Counting entry = new Counting();
                    concurrent.schedule(entry, lane);
                    if (i % 3 != 0 && entry.cancel()) {
                        cancelled.incrementAndGet();
                    }
                }
                finished.countDown();
            });
        }
        finished.await();
        done.set(true);
        ticker.join();
        concurrent.tick(Long.MAX_VALUE / 4);
        concurrent.tick(Long.MAX_VALUE / 2);

        assertThat(concurrent.pending()).isZero();
        assertThat(fired.size() + cancelled.get()).isEqualTo(threads * perThread);
        for (Runnable entry : fired) {
            assertThat(((TimerEntry) entry).state()).isEqualTo(TimerEntry.FIRED);
        }
    }

    // TimeoutPolicy.executeAsync on a manual timer.

    private TimeoutPolicy policy(Duration timeout) {
        return new TimeoutPolicy("test", TimeoutConfig.builder().timeout(timeout).build(), new Watchdog(0), timer);
    }

    @Test
    void aStageThatIsStillPendingAtTheDeadlineTimesOutAndIsCancelled() {
        TimeoutPolicy timeout = policy(Duration.ofMillis(100));
        CompletableFuture<String> pending = new CompletableFuture<>();
        CompletableFuture<String> result = timeout.executeAsync(() -> pending);
        timer.tick(0);
        timer.tick(100 * MS);
        assertThat(result).isNotDone();
        runLaunched();

        assertThat(result).isCompletedExceptionally();
        assertThatThrownBy(result::join).cause().isInstanceOf(CallTimeoutException.class);
        assertThat(pending).isCancelled();
        assertThat(timer.pending()).isZero();
    }

    @Test
    void aStageThatCompletesInTimeReleasesItsDeadline() {
        TimeoutPolicy timeout = policy(Duration.ofHours(1));
        CompletableFuture<String> early = new CompletableFuture<>();
        CompletableFuture<String> late = new CompletableFuture<>();
        CompletableFuture<String> first = timeout.executeAsync(() -> early);
        early.complete("early"); // before the timer took it
        CompletableFuture<String> second = timeout.executeAsync(() -> late);
        timer.tick(0);
        late.completeExceptionally(new IOException("down")); // after
        timer.tick(MS);

        assertThat(first.join()).isEqualTo("early");
        assertThatThrownBy(second::join).cause().isInstanceOf(IOException.class);
        assertThat(timer.pending()).isZero();
        timer.tick(Duration.ofHours(2).toNanos());
        assertThat(launched).isEmpty();
    }

    @Test
    void aStageAlreadyCompleteNeedsNoTimer() {
        TimeoutPolicy timeout = policy(Duration.ofMillis(100));
        assertThat(timeout.executeAsync(() -> CompletableFuture.completedFuture("x")).join()).isEqualTo("x");
        CompletableFuture<String> failed = timeout.executeAsync(() -> CompletableFuture.failedFuture(new IOException()));
        assertThatThrownBy(failed::join).cause().isInstanceOf(IOException.class);
        CompletableFuture<String> wrapped = timeout.executeAsync(() -> CompletableFuture.failedFuture(
                new java.util.concurrent.CompletionException(new IllegalStateException())));
        assertThatThrownBy(wrapped::join).cause().as("unwrapped").isInstanceOf(IllegalStateException.class);
        assertThat(timer.pending()).as("nothing was scheduled").isZero();

        CompletableFuture<String> cancelled = new CompletableFuture<>();
        cancelled.cancel(false);
        assertThat(timeout.executeAsync(() -> cancelled)).isCompletedExceptionally();
    }

    @Test
    void whenTheStageCompletesAfterTheTimerFiredButBeforeTheTimeoutRanTheFirstCompletionWins() {
        // Both orders of the race between the stage completing and the fired timeout running.
        TimeoutPolicy timeout = policy(Duration.ofMillis(100));
        CompletableFuture<String> pending = new CompletableFuture<>();
        CompletableFuture<String> result = timeout.executeAsync(() -> pending);
        timer.tick(0);
        timer.tick(100 * MS);
        pending.complete("just in time");
        runLaunched();
        assertThat(result.join()).isEqualTo("just in time");
        assertThat(pending.isCancelled()).isFalse();

        CompletableFuture<String> other = new CompletableFuture<>();
        CompletableFuture<String> timedOut = timeout.executeAsync(() -> other);
        timer.tick(200 * MS);
        timer.tick(300 * MS);
        runLaunched();
        assertThat(other.complete("too late")).as("cancelled by the timeout").isFalse();
        assertThatThrownBy(timedOut::join).cause().isInstanceOf(CallTimeoutException.class);
    }

    @Test
    void aMinimalStageIsTimedLikeAnyOther() {
        TimeoutPolicy timeout = policy(Duration.ofMillis(100));
        CompletableFuture<String> source = CompletableFuture.completedFuture("x");
        assertThat(timeout.executeAsync(source::minimalCompletionStage).join()).isEqualTo("x");
        timer.tick(0);
        assertThat(timer.pending()).isZero();
    }
}
