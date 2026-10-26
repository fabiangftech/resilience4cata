package dev.resilience4cata.generated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.AdaptiveLimitConfig;
import dev.resilience4cata.AdaptiveLimitConfig.Algorithm;
import dev.resilience4cata.AdaptiveLimitSnapshot;
import dev.resilience4cata.BulkheadFullException;
import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.LimitExceededException;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AdaptiveLimitPolicyTest {

    private static final long MS = 1_000_000L;
    private static final long WINDOW = 1_000 * MS;

    private final AtomicLong now = new AtomicLong(5_000 * MS);
    /** The end of the current window, as the policy computes it: one window after creation, then after each end. */
    private long deadline = now.get() + WINDOW;

    private AdaptiveLimitPolicy policy(AdaptiveLimitConfig.Builder config) {
        return new AdaptiveLimitPolicy("limit", config.build(), PolicyEnvironment.forTest(now::get, now::addAndGet), 4);
    }

    /** One window: {@code calls} calls in flight together, each taking {@code rtt}, ending at the deadline. */
    private void window(AdaptiveLimitPolicy policy, int calls, long rtt) {
        now.set(deadline - rtt);
        List<Long> starts = new ArrayList<>();
        for (int i = 0; i < calls; i++) {
            starts.add(policy.acquire());
        }
        now.set(deadline);
        starts.forEach(policy::onSuccess);
        deadline += WINDOW;
    }

    @Test
    void rejectsOnceFullAndAcceptsAgainAfterACallEnds() {
        AdaptiveLimitPolicy policy = policy(AdaptiveLimitConfig.builder().initialLimit(2));
        long first = policy.acquire();
        policy.acquire();
        assertThatThrownBy(policy::acquire).isSameAs(policy.rejection()).isInstanceOf(LimitExceededException.class)
                .hasMessage("Adaptive limit 'limit' is full").hasNoCause();
        assertThat(policy.inFlight()).isEqualTo(2);

        policy.onSuccess(first);
        assertThat(policy.inFlight()).isEqualTo(1);
        policy.acquire();
        assertThat(policy.inFlight()).isEqualTo(2);
    }

    @Test
    void everyOutcomeFreesTheSlot() {
        AdaptiveLimitPolicy policy = policy(AdaptiveLimitConfig.builder().initialLimit(1));
        policy.onSuccess(policy.acquire());
        policy.onFailure(policy.acquire(), new IOException("ignored"));
        policy.onFailure(policy.acquire(), new CallTimeoutException("t", Duration.ofSeconds(1), null));
        policy.acquire();
        policy.release();
        assertThat(policy.inFlight()).isZero();
        policy.acquire();
        assertThat(policy.inFlight()).isEqualTo(1);
    }

    @Test
    void aDisabledLimitLimitsNothingAndNeverEndsAWindow() {
        AdaptiveLimitPolicy policy = policy(AdaptiveLimitConfig.builder().enabled(false).initialLimit(1).minWindowSamples(1));
        for (int i = 0; i < 10; i++) {
            policy.acquire();
        }
        now.addAndGet(10 * WINDOW);
        policy.onSuccess(0);
        assertThat(policy.inFlight()).isZero();
        assertThat(policy.windowsForTest()).isZero();
    }

    @Test
    void aWindowEndsAtItsDeadlineOnceItHasEnoughSamples() {
        AdaptiveLimitPolicy policy = policy(AdaptiveLimitConfig.builder().minWindowSamples(3));
        now.set(deadline - 1);
        policy.onSuccess(policy.acquire());
        policy.onSuccess(policy.acquire());
        policy.onSuccess(policy.acquire());
        assertThat(policy.windowsForTest()).as("before the deadline").isZero();

        now.set(deadline);
        policy.onSuccess(policy.acquire());
        assertThat(policy.windowsForTest()).as("four samples at the deadline").isEqualTo(1);
        assertThat(policy.snapshot().windowLatency()).as("calls that took no time count as 1 ns")
                .isEqualTo(Duration.ofNanos(1));
    }

    @Test
    void anUnderSampledWindowIsExtendedAndKeepsItsSamples() {
        AdaptiveLimitPolicy policy = policy(AdaptiveLimitConfig.builder().algorithm(Algorithm.AIMD).initialLimit(10)
                .minWindowSamples(3));
        long[] latencies = {10 * MS, 20 * MS, 30 * MS};
        for (int i = 0; i < 2; i++) { // one call per window, two windows: never enough samples
            now.set(deadline - latencies[i]);
            long start = policy.acquire();
            now.set(deadline);
            policy.onSuccess(start); // over, too few samples: the deadline moves one window on
            assertThat(policy.windowsForTest()).isZero();
            assertThat(policy.limit()).as("no update").isEqualTo(10);
            deadline += WINDOW;
        }
        now.set(deadline - 1);
        policy.onSuccess(policy.acquire());
        assertThat(policy.windowsForTest()).as("the deadline moved: a sample before it ends nothing").isZero();

        now.set(deadline - latencies[2]);
        long start = policy.acquire();
        now.set(deadline);
        policy.onSuccess(start); // four samples kept across the extensions: the window ends, once
        assertThat(policy.windowsForTest()).isEqualTo(1);
        assertThat(policy.snapshot().windowLatency()).as("the average of every kept sample")
                .isEqualTo(Duration.ofNanos((10 * MS + 20 * MS + 1 + 30 * MS) / 4));
        assertThat(policy.limit()).as("AIMD: at least half of 10 was never in flight").isEqualTo(10);
        policy.onSuccess(policy.acquire());
        assertThat(policy.windowsForTest()).as("exactly one update").isEqualTo(1);
    }

    @Test
    void ignoredFailuresAreNotSamples() {
        AdaptiveLimitPolicy policy = policy(AdaptiveLimitConfig.builder().minWindowSamples(1));
        now.set(deadline);
        policy.onFailure(policy.acquire(), new IOException("not in dropOn"));
        assertThat(policy.windowsForTest()).isZero();
        policy.onSuccess(policy.acquire());
        assertThat(policy.windowsForTest()).isEqualTo(1);
    }

    @Test
    void dropOnFailuresMakeAimdBackOffAndIgnoreOnWins() {
        AdaptiveLimitPolicy policy = policy(AdaptiveLimitConfig.builder().algorithm(Algorithm.AIMD).initialLimit(20)
                .minWindowSamples(1).dropOn(List.of(TimeoutException.class, BulkheadFullException.class))
                .ignoreOn(List.of(BulkheadFullException.class)));
        now.set(deadline);
        policy.onFailure(policy.acquire(), new BulkheadFullException("b"));
        assertThat(policy.windowsForTest()).as("ignoreOn wins over dropOn").isZero();
        policy.onFailure(policy.acquire(), new TimeoutException());
        assertThat(policy.windowsForTest()).isEqualTo(1);
        assertThat(policy.limit()).as("20 * 0.9").isEqualTo(18);
    }

    @Test
    void theDefaultDropsAreTimeoutsAndFullBulkheads() {
        AdaptiveLimitPolicy policy = policy(AdaptiveLimitConfig.builder().algorithm(Algorithm.AIMD).initialLimit(100)
                .minWindowSamples(1));
        now.set(deadline);
        policy.onFailure(policy.acquire(), new CallTimeoutException("t", Duration.ofSeconds(1), null));
        assertThat(policy.limit()).isEqualTo(90);
        deadline += WINDOW;
        now.set(deadline);
        policy.onFailure(policy.acquire(), new BulkheadFullException("b"));
        assertThat(policy.limit()).isEqualTo(81);
    }

    @Test
    void gradient2GrowsTheLimitWhileLatencyHoldsUnderLoad() {
        AdaptiveLimitPolicy policy = policy(AdaptiveLimitConfig.builder().initialLimit(10).minWindowSamples(1));
        for (int i = 0; i < 20; i++) {
            window(policy, policy.limit(), 10 * MS);
        }
        assertThat(policy.limit()).isGreaterThan(20);
        AdaptiveLimitSnapshot snapshot = policy.snapshot();
        assertThat(snapshot.windows()).isEqualTo(20);
        assertThat(snapshot.windowLatency()).isEqualTo(Duration.ofMillis(10));
        assertThat(snapshot.longTermLatency().toNanos()).isCloseTo(10 * MS, org.assertj.core.data.Offset.offset(10L));
        assertThat(snapshot.algorithm()).isEqualTo(Algorithm.GRADIENT2);
        assertThat(snapshot.inFlight()).isZero();
    }

    @Test
    void gradient2LowersTheLimitWhenLatencyRises() {
        AdaptiveLimitPolicy policy = policy(AdaptiveLimitConfig.builder().initialLimit(50).minWindowSamples(1));
        for (int i = 0; i < 10; i++) {
            window(policy, policy.limit(), 10 * MS);
        }
        int before = policy.limit();
        for (int i = 0; i < 10; i++) {
            window(policy, policy.limit(), 100 * MS);
        }
        assertThat(policy.limit()).isLessThan(before / 2);
    }

    @Test
    void gradient2DoesNotGrowWhenTheCallsUseLessThanHalfTheLimit() {
        AdaptiveLimitPolicy policy = policy(AdaptiveLimitConfig.builder().initialLimit(20).minWindowSamples(1));
        for (int i = 0; i < 20; i++) {
            window(policy, 9, 10 * MS);
        }
        assertThat(policy.limit()).isEqualTo(20);
    }

    @Test
    void theLimitStaysWithinItsBounds() {
        AdaptiveLimitPolicy policy = policy(AdaptiveLimitConfig.builder().algorithm(Algorithm.AIMD).initialLimit(3)
                .minLimit(2).maxLimit(4).minWindowSamples(1));
        for (int i = 0; i < 5; i++) {
            window(policy, policy.limit(), MS);
        }
        assertThat(policy.limit()).isEqualTo(4);
        for (int i = 0; i < 5; i++) {
            now.set(deadline);
            policy.onFailure(policy.acquire(), new TimeoutException());
            deadline += WINDOW;
        }
        assertThat(policy.limit()).isEqualTo(2);
    }

    @Test
    void aLowerLimitRejectsUntilEnoughCallsEnd() {
        AdaptiveLimitPolicy policy = policy(AdaptiveLimitConfig.builder().algorithm(Algorithm.AIMD).initialLimit(10)
                .minWindowSamples(1));
        List<Long> running = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            running.add(policy.acquire());
        }
        now.set(deadline);
        policy.onFailure(policy.acquire(), new TimeoutException()); // drop: the limit goes to 9, with 9 in flight
        assertThat(policy.limit()).isEqualTo(9);
        assertThatThrownBy(policy::acquire).isSameAs(policy.rejection());
        policy.onSuccess(running.removeFirst());
        policy.acquire();
        assertThat(policy.inFlight()).isEqualTo(9);
    }

    @Test
    void onlyTheThreadThatHoldsTheWindowLockEndsTheWindow() {
        AdaptiveLimitPolicy policy = policy(AdaptiveLimitConfig.builder().minWindowSamples(1));
        now.set(deadline);
        assertThat(policy.tryLockWindowForTest()).isTrue();
        policy.onSuccess(policy.acquire()); // over and with a sample, but another thread is ending the window
        assertThat(policy.windowsForTest()).isZero();
        assertThat(policy.tryLockWindowForTest()).as("the lock is held").isFalse();
        policy.unlockWindowForTest();
        policy.endWindowForTest(now.get());
        assertThat(policy.windowsForTest()).isEqualTo(1);
        policy.endWindowForTest(now.get());
        assertThat(policy.windowsForTest()).as("the deadline moved: nothing to end").isEqualTo(1);
    }

    @Test
    void asyncCallsHoldTheirSlotUntilTheirStageCompletes() {
        AdaptiveLimitPolicy policy = policy(AdaptiveLimitConfig.builder().initialLimit(1).minWindowSamples(1));
        CompletableFuture<String> stage = new CompletableFuture<>();
        CompletableFuture<String> result = policy.executeAsync(() -> stage);
        assertThat(policy.inFlight()).isEqualTo(1);
        assertThat(policy.executeAsync(() -> CompletableFuture.completedFuture("x")))
                .isCompletedExceptionally().failsWithin(Duration.ZERO).withThrowableThat().havingCause()
                .isSameAs(policy.rejection());

        now.set(deadline);
        stage.complete("done");
        assertThat(result).isCompletedWithValue("done");
        assertThat(policy.inFlight()).isZero();
        assertThat(policy.windowsForTest()).as("the success was a sample").isEqualTo(1);
    }

    @Test
    void anAsyncFailureIsClassifiedAndACancelledCallIsNotASample() {
        AdaptiveLimitPolicy policy = policy(AdaptiveLimitConfig.builder().algorithm(Algorithm.AIMD).initialLimit(10)
                .minWindowSamples(1));
        now.set(deadline);
        CompletableFuture<String> cancelled = new CompletableFuture<>();
        CompletableFuture<String> result = policy.executeAsync(() -> cancelled);
        result.cancel(true);
        assertThat(cancelled).isCancelled();
        assertThat(policy.inFlight()).isZero();
        assertThat(policy.windowsForTest()).isZero();

        CompletableFuture<String> failed = policy.executeAsync(
                () -> CompletableFuture.failedFuture(new CallTimeoutException("t", Duration.ofSeconds(1), null)));
        assertThat(failed).isCompletedExceptionally();
        assertThat(policy.inFlight()).isZero();
        assertThat(policy.limit()).as("a drop").isEqualTo(9);
    }

    @Test
    void theSnapshotReadsTheCurrentValues() {
        AdaptiveLimitPolicy policy = policy(AdaptiveLimitConfig.builder().algorithm(Algorithm.AIMD).initialLimit(5)
                .minLimit(2).maxLimit(9));
        policy.acquire();
        policy.acquire();
        AdaptiveLimitSnapshot snapshot = policy.snapshot();
        assertThat(snapshot).isEqualTo(new AdaptiveLimitSnapshot("limit", Algorithm.AIMD, 5, 2, 2, 9, 0, Duration.ZERO,
                Duration.ZERO));
        assertThat(snapshot.availableSlots()).isEqualTo(3);
    }
}
