package dev.resilience4cata.generated;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.BulkheadConfig;
import dev.resilience4cata.BulkheadSnapshot;
import dev.resilience4cata.CircuitBreakerConfig;
import dev.resilience4cata.CircuitBreakerSnapshot;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.RateLimiterConfig;
import dev.resilience4cata.RateLimiterSnapshot;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SnapshotTest {

    private static final long MS = 1_000_000L;

    private final AtomicLong now = new AtomicLong(1_000 * MS);

    private CircuitBreakerPolicy breaker(CircuitBreakerConfig.Builder config) {
        return new CircuitBreakerPolicy("cb", config.build(), now::get);
    }

    private void call(CircuitBreakerPolicy cb, boolean fails, long durationNanos) {
        long token = cb.acquire();
        long start = cb.startTimer();
        now.addAndGet(durationNanos);
        if (fails) {
            cb.onFailure(token, start, new IOException());
        } else {
            cb.onSuccess(token, start);
        }
    }

    @Test
    void ratesAreMinusOneUntilTheMinimumCallsAreRecorded() {
        CircuitBreakerPolicy cb = breaker(CircuitBreakerConfig.builder().windowSize(10).minimumCalls(4));
        assertThat(cb.snapshot()).isEqualTo(new CircuitBreakerSnapshot("cb", CircuitBreakerState.CLOSED, -1f, -1f, 0, 0, 0));
        call(cb, true, 0);
        call(cb, false, 0);
        call(cb, false, 0);
        assertThat(cb.snapshot()).isEqualTo(new CircuitBreakerSnapshot("cb", CircuitBreakerState.CLOSED, -1f, -1f, 3, 1, 0));
        call(cb, false, 0);
        assertThat(cb.snapshot()).as("slow-call detection is off: no slow-call rate")
                .isEqualTo(new CircuitBreakerSnapshot("cb", CircuitBreakerState.CLOSED, 25f, -1f, 4, 1, 0));
    }

    @Test
    void slowCallsAreCountedWhenDetectionIsOn() {
        CircuitBreakerPolicy cb = breaker(CircuitBreakerConfig.builder().windowSize(4).minimumCalls(2)
                .failureRateThreshold(100).slowCallRateThreshold(100).slowCallDuration(Duration.ofMillis(10)));
        call(cb, false, 20 * MS);
        call(cb, true, 0);
        call(cb, false, 0);
        call(cb, false, 0);
        assertThat(cb.snapshot()).isEqualTo(new CircuitBreakerSnapshot("cb", CircuitBreakerState.CLOSED, 25f, 25f, 4, 1, 1));
    }

    @Test
    void theWindowSlides() {
        CircuitBreakerPolicy cb = breaker(CircuitBreakerConfig.builder().windowSize(2).minimumCalls(2).failureRateThreshold(100));
        call(cb, true, 0);
        call(cb, false, 0);
        call(cb, false, 0); // replaces the failure
        assertThat(cb.snapshot()).isEqualTo(new CircuitBreakerSnapshot("cb", CircuitBreakerState.CLOSED, 0f, -1f, 2, 0, 0));
    }

    @Test
    void anOpenBreakerHasAnEmptyWindowAndAHalfOpenOneCountsItsProbes() {
        CircuitBreakerPolicy cb = breaker(CircuitBreakerConfig.builder().windowSize(2).minimumCalls(2)
                .waitInOpen(Duration.ofSeconds(1)).halfOpenCalls(2));
        call(cb, true, 0);
        call(cb, true, 0);
        assertThat(cb.snapshot()).isEqualTo(new CircuitBreakerSnapshot("cb", CircuitBreakerState.OPEN, -1f, -1f, 0, 0, 0));

        now.addAndGet(Duration.ofSeconds(1).toNanos());
        call(cb, true, 0);
        assertThat(cb.snapshot()).as("1 of 2 probes")
                .isEqualTo(new CircuitBreakerSnapshot("cb", CircuitBreakerState.HALF_OPEN, -1f, -1f, 1, 1, 0));

        cb.forceOpen();
        assertThat(cb.snapshot().state()).isEqualTo(CircuitBreakerState.FORCED_OPEN);
        cb.disable();
        assertThat(cb.snapshot()).isEqualTo(new CircuitBreakerSnapshot("cb", CircuitBreakerState.DISABLED, -1f, -1f, 0, 0, 0));
    }

    @Test
    void aTimeWindowCountsTheCallsOfItsSeconds() {
        CircuitBreakerPolicy cb = breaker(CircuitBreakerConfig.builder().windowType(CircuitBreakerConfig.WindowType.TIME)
                .windowSize(2).minimumCalls(2).failureRateThreshold(100));
        call(cb, true, 0);
        call(cb, false, 0);
        call(cb, false, 0);
        CircuitBreakerSnapshot snapshot = cb.snapshot();
        assertThat(snapshot.bufferedCalls()).isEqualTo(3);
        assertThat(snapshot.failedCalls()).isEqualTo(1);
        assertThat(snapshot.failureRate()).isCloseTo(33.3f, org.assertj.core.data.Offset.offset(0.1f));

        now.addAndGet(Duration.ofSeconds(3).toNanos());
        assertThat(cb.snapshot().bufferedCalls()).as("all buckets expired").isZero();
    }

    @Test
    void bulkheadSnapshotsCountRunningAndWaitingCalls() {
        BulkheadPolicy bulkhead = new BulkheadPolicy("b", BulkheadConfig.builder().maxConcurrent(2)
                .maxWait(Duration.ofSeconds(10)).build());
        assertThat(bulkhead.tryEnter()).isTrue();
        assertThat(bulkhead.snapshot()).isEqualTo(new BulkheadSnapshot("b", 1, 0, 2));
        assertThat(bulkhead.snapshot().availableSlots()).isEqualTo(1);
        assertThat(bulkhead.tryEnter()).isTrue();
        CompletableFuture<Integer> queued = bulkhead.executeAsync(() -> CompletableFuture.completedFuture(1));
        assertThat(bulkhead.snapshot()).isEqualTo(new BulkheadSnapshot("b", 2, 1, 2));
        bulkhead.exit();
        assertThat(queued.join()).isEqualTo(1);
        bulkhead.exit();
        assertThat(bulkhead.snapshot()).isEqualTo(new BulkheadSnapshot("b", 0, 0, 2));
    }

    @Test
    void rateLimiterSnapshotsShowTheAvailablePermits() {
        RateLimiterPolicy limiter = new RateLimiterPolicy("r",
                RateLimiterConfig.builder().permits(3).period(Duration.ofSeconds(3)).build(), now::get, nanos -> { });
        assertThat(limiter.snapshot()).isEqualTo(new RateLimiterSnapshot("r", 3, 3));
        limiter.tryAcquire();
        limiter.tryAcquire();
        assertThat(limiter.snapshot()).isEqualTo(new RateLimiterSnapshot("r", 1, 3));
        now.addAndGet(Duration.ofSeconds(1).toNanos());
        assertThat(limiter.snapshot().availablePermits()).isEqualTo(2);
    }
}
