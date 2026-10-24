package dev.resilience4cata.generated;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.CircuitBreakerConfig;
import dev.resilience4cata.CircuitBreakerState;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CircuitBreakerPolicyTest {

    private final AtomicLong now = new AtomicLong(1_000_000);

    private CircuitBreakerPolicy breaker(CircuitBreakerConfig.Builder config) {
        return new CircuitBreakerPolicy("test", config.build(), now::get);
    }

    private static CircuitBreakerConfig.Builder window(int size) {
        return CircuitBreakerConfig.builder().windowSize(size).minimumCalls(size)
                .failureRateThreshold(50).waitInOpen(Duration.ofSeconds(10)).halfOpenCalls(2);
    }

    private static void succeed(CircuitBreakerPolicy cb) {
        long token = cb.acquire();
        assertThat(token).isNotNegative();
        cb.onSuccess(token, cb.startTimer());
    }

    private static void fail(CircuitBreakerPolicy cb, Throwable failure) {
        long token = cb.acquire();
        assertThat(token).isNotNegative();
        cb.onFailure(token, cb.startTimer(), failure);
    }

    private static void fail(CircuitBreakerPolicy cb) {
        fail(cb, new IOException("boom"));
    }

    @Test
    void opensWhenTheFailureRateReachesTheThreshold() {
        CircuitBreakerPolicy cb = breaker(window(4));
        succeed(cb);
        succeed(cb);
        fail(cb);
        assertThat(cb.state()).isEqualTo(CircuitBreakerState.CLOSED); // only 3 of 4 minimum calls

        fail(cb);
        assertThat(cb.state()).isEqualTo(CircuitBreakerState.OPEN);
        assertThat(cb.acquire()).isEqualTo(CircuitBreakerPolicy.REJECTED);
    }

    @Test
    void staysClosedBelowTheThreshold() {
        CircuitBreakerPolicy cb = breaker(window(4));
        for (int i = 0; i < 20; i++) {
            succeed(cb);
            succeed(cb);
            succeed(cb);
            fail(cb);
        }
        assertThat(cb.state()).isEqualTo(CircuitBreakerState.CLOSED);
    }

    @Test
    void opensWhenASuccessCompletesTheMinimumCalls() {
        CircuitBreakerPolicy cb = breaker(window(4));
        fail(cb);
        fail(cb);
        fail(cb);
        assertThat(cb.state()).isEqualTo(CircuitBreakerState.CLOSED);

        succeed(cb); // 3 failures out of 4 calls
        assertThat(cb.state()).isEqualTo(CircuitBreakerState.OPEN);
    }

    @Test
    void theWindowSlides() {
        CircuitBreakerPolicy cb = breaker(window(4).failureRateThreshold(75));
        fail(cb);
        fail(cb);
        succeed(cb);
        succeed(cb); // 50%
        succeed(cb); // replaces a failure: 25%
        fail(cb);    // replaces a failure: 25%
        fail(cb);    // replaces a success: 50%
        assertThat(cb.state()).isEqualTo(CircuitBreakerState.CLOSED);
        fail(cb);    // replaces a success: 75%
        assertThat(cb.state()).isEqualTo(CircuitBreakerState.OPEN);
    }

    @Test
    void probesAfterTheWaitAndClosesWhenProbesSucceed() {
        CircuitBreakerPolicy cb = breaker(window(2));
        fail(cb);
        fail(cb);
        assertThat(cb.state()).isEqualTo(CircuitBreakerState.OPEN);

        now.addAndGet(Duration.ofSeconds(9).toNanos());
        assertThat(cb.acquire()).isEqualTo(CircuitBreakerPolicy.REJECTED);

        now.addAndGet(Duration.ofSeconds(1).toNanos());
        long first = cb.acquire();
        long second = cb.acquire();
        assertThat(cb.state()).isEqualTo(CircuitBreakerState.HALF_OPEN);
        assertThat(first).isNotNegative();
        assertThat(second).isEqualTo(first);
        assertThat(cb.acquire()).isEqualTo(CircuitBreakerPolicy.REJECTED); // only 2 probes

        cb.onSuccess(first, 0);
        assertThat(cb.state()).isEqualTo(CircuitBreakerState.HALF_OPEN);
        cb.onSuccess(second, 0);
        assertThat(cb.state()).isEqualTo(CircuitBreakerState.CLOSED);

        succeed(cb); // the new closed window starts empty
        assertThat(cb.state()).isEqualTo(CircuitBreakerState.CLOSED);
    }

    @Test
    void reopensWhenProbesFail() {
        CircuitBreakerPolicy cb = breaker(window(2));
        fail(cb);
        fail(cb);
        now.addAndGet(Duration.ofSeconds(10).toNanos());

        succeed(cb);
        fail(cb);
        assertThat(cb.state()).isEqualTo(CircuitBreakerState.OPEN);
        assertThat(cb.acquire()).isEqualTo(CircuitBreakerPolicy.REJECTED);
    }

    @Test
    void outcomesOfCallsStartedInAnEarlierPhaseAreDropped() {
        CircuitBreakerPolicy cb = breaker(window(2));
        long slowCall = cb.acquire(); // starts while closed
        fail(cb);
        fail(cb);
        now.addAndGet(Duration.ofSeconds(10).toNanos());
        long probe = cb.acquire();
        assertThat(cb.state()).isEqualTo(CircuitBreakerState.HALF_OPEN);

        cb.onSuccess(slowCall, 0); // must not count as a probe
        cb.onSuccess(probe, 0);
        assertThat(cb.state()).isEqualTo(CircuitBreakerState.HALF_OPEN);
    }

    @Test
    void ignoredExceptionsAreNotCountedAndGiveBackTheProbe() {
        CircuitBreakerPolicy cb = breaker(window(2).halfOpenCalls(1).ignoreOn(List.of(IllegalArgumentException.class)));
        fail(cb, new IllegalArgumentException());
        fail(cb, new IllegalArgumentException());
        fail(cb, new IllegalArgumentException());
        assertThat(cb.state()).isEqualTo(CircuitBreakerState.CLOSED);

        fail(cb);
        fail(cb);
        now.addAndGet(Duration.ofSeconds(10).toNanos());
        long probe = cb.acquire();
        assertThat(cb.acquire()).isEqualTo(CircuitBreakerPolicy.REJECTED);
        cb.onFailure(probe, 0, new IllegalArgumentException());
        assertThat(cb.acquire()).isNotNegative(); // the permit came back
    }

    @Test
    void exceptionsOutsideRecordOnCountAsSuccesses() {
        CircuitBreakerPolicy cb = breaker(window(2).recordOn(List.of(IOException.class)));
        fail(cb, new IllegalStateException());
        fail(cb, new IllegalStateException());
        assertThat(cb.state()).isEqualTo(CircuitBreakerState.CLOSED);
    }

    @Test
    void slowCallsOpenTheBreaker() {
        CircuitBreakerPolicy cb = breaker(window(2).slowCallDuration(Duration.ofMillis(100)).slowCallRateThreshold(100));
        for (int i = 0; i < 2; i++) {
            long token = cb.acquire();
            long start = cb.startTimer();
            now.addAndGet(Duration.ofMillis(150).toNanos());
            cb.onSuccess(token, start);
        }
        assertThat(cb.state()).isEqualTo(CircuitBreakerState.OPEN);
    }

    @Test
    void doesNotReadTheClockWithoutSlowCallDetection() {
        List<String> reads = new ArrayList<>();
        CircuitBreakerPolicy cb = new CircuitBreakerPolicy("test", window(100).build(), () -> {
            reads.add("read");
            return 0;
        });
        for (int i = 0; i < 1000; i++) {
            succeed(cb);
        }
        assertThat(reads).isEmpty();
    }

    @Test
    void disabledNeverOpens() {
        CircuitBreakerPolicy cb = breaker(window(2).enabled(false));
        for (int i = 0; i < 10; i++) {
            fail(cb);
        }
        assertThat(cb.state()).isEqualTo(CircuitBreakerState.CLOSED);
    }

    @Test
    void aWindowFullOfSuccessesStillOpensOnFailures() {
        CircuitBreakerPolicy cb = breaker(window(4));
        for (int i = 0; i < 10; i++) {
            succeed(cb); // the window becomes quiet after 4
        }
        fail(cb);
        assertThat(cb.state()).isEqualTo(CircuitBreakerState.CLOSED); // 1 of 4
        fail(cb);
        assertThat(cb.state()).isEqualTo(CircuitBreakerState.OPEN);   // 2 of 4
    }

    @Test
    void leavesAndReentersTheQuietStateExactly() {
        CircuitBreakerPolicy cb = breaker(window(4).failureRateThreshold(75));
        for (int i = 0; i < 4; i++) {
            succeed(cb);
        }
        fail(cb);                      // S S S F (oldest success replaced)
        for (int i = 0; i < 4; i++) {
            succeed(cb);               // the failure is evicted: quiet again
        }
        fail(cb);
        fail(cb);
        assertThat(cb.state()).isEqualTo(CircuitBreakerState.CLOSED); // 2 of 4
        fail(cb);
        assertThat(cb.state()).isEqualTo(CircuitBreakerState.OPEN);   // 3 of 4: no stale failure was kept
    }

    @Test
    void quietStateSurvivesConcurrentFailures() throws InterruptedException {
        // Threads alternate long runs of successes (quiet) with bursts of failures. With a 100%
        // threshold the breaker never opens, so afterwards the counters must still match the slots
        // and a burst of failures must still open it.
        CircuitBreakerPolicy cb = breaker(window(32).failureRateThreshold(100));
        IOException failure = new IOException();
        List<Thread> workers = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < 8; t++) {
            int seed = t;
            workers.add(Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < 100_000; i++) {
                    long token = cb.acquire();
                    if ((i + seed) % 97 < 3) {
                        cb.onFailure(token, 0, failure);
                    } else {
                        cb.onSuccess(token, 0);
                    }
                }
            }));
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join();
        }
        int[] counts = cb.closedWindowCounts();
        assertThat(counts[0]).isEqualTo(32).isEqualTo(counts[3]);
        assertThat(counts[1]).isEqualTo(counts[4]);
    }

    @Test
    void countersMatchTheSlotsAfterConcurrentRecording() throws InterruptedException {
        // A threshold of 100% and failures on odd calls only: the breaker never opens, so every
        // thread records into the same window.
        CircuitBreakerPolicy cb = breaker(window(64).failureRateThreshold(100));
        int threads = 8;
        int callsPerThread = 200_000;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        IOException failure = new IOException();
        for (int t = 0; t < threads; t++) {
            Thread worker = Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < callsPerThread; i++) {
                    long token = cb.acquire();
                    if ((i & 1) == 0) {
                        cb.onSuccess(token, 0);
                    } else {
                        cb.onFailure(token, 0, failure);
                    }
                }
            });
            workers.add(worker);
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join();
        }

        assertThat(cb.state()).isEqualTo(CircuitBreakerState.CLOSED);
        int[] counts = cb.closedWindowCounts();
        assertThat(counts[0]).as("calls").isEqualTo(64).isEqualTo(counts[3]);
        assertThat(counts[1]).as("failures").isEqualTo(counts[4]);
        assertThat(counts[2]).as("slow").isZero().isEqualTo(counts[5]);
    }
}
