package dev.resilience4cata.generated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.RateLimiterConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RateLimiterPolicyTest {

    private static final long MS = 1_000_000L;

    private long now = 1_000 * MS;
    private final List<Long> sleeps = new ArrayList<>();

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    private RateLimiterPolicy limiter(int permits, Duration period, Duration maxWait) {
        RateLimiterConfig config = RateLimiterConfig.builder().permits(permits).period(period).maxWait(maxWait).build();
        return new RateLimiterPolicy("test", config, () -> now, nanos -> {
            sleeps.add(nanos);
            now += nanos;
        });
    }

    @Test
    void allowsAFullBurstThenOnePermitPerInterval() {
        RateLimiterPolicy limiter = limiter(5, Duration.ofSeconds(1), Duration.ZERO); // one permit every 200 ms
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire()).as("burst call %d", i).isTrue();
        }
        assertThat(limiter.tryAcquire()).isFalse();

        now += 199 * MS;
        assertThat(limiter.tryAcquire()).isFalse();
        now += MS;
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
        assertThat(sleeps).isEmpty();
    }

    @Test
    void rejectionsDoNotUseUpPermits() {
        RateLimiterPolicy limiter = limiter(2, Duration.ofSeconds(1), Duration.ZERO);
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        for (int i = 0; i < 100; i++) {
            assertThat(limiter.tryAcquire()).isFalse();
        }

        now += 500 * MS; // exactly one interval: exactly one permit, as if nobody had been rejected
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
    }

    @Test
    void unusedPermitsBuildUpToTheBurstOnly() {
        RateLimiterPolicy limiter = limiter(3, Duration.ofSeconds(3), Duration.ZERO);
        assertThat(limiter.availablePermits()).isEqualTo(3);
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.availablePermits()).isEqualTo(1);

        now += 1_000 * MS;
        assertThat(limiter.availablePermits()).isEqualTo(2);
        now += 60_000 * MS;
        assertThat(limiter.availablePermits()).isEqualTo(3);
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire()).isTrue();
        }
        assertThat(limiter.tryAcquire()).isFalse();
        assertThat(limiter.availablePermits()).isZero();
    }

    @Test
    void waitsForAPermitThatComesWithinMaxWait() {
        RateLimiterPolicy limiter = limiter(1, Duration.ofSeconds(1), Duration.ofMillis(300));
        assertThat(limiter.tryAcquire()).isTrue();

        assertThat(limiter.tryAcquire()).as("next permit in 1 s").isFalse();
        assertThat(sleeps).isEmpty();

        now += 800 * MS;
        assertThat(limiter.tryAcquire()).as("next permit in 200 ms").isTrue();
        assertThat(sleeps).containsExactly(200 * MS);

        // The permit was reserved before sleeping, so the next one is a full interval later.
        now += 650 * MS;
        assertThat(limiter.tryAcquire()).as("next permit in 350 ms").isFalse();
        now += 50 * MS;
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(sleeps).containsExactly(200 * MS, 300 * MS);
    }

    @Test
    void waitersReserveConsecutiveSlots() {
        RateLimiterPolicy limiter = limiter(10, Duration.ofSeconds(1), Duration.ofSeconds(1));
        List<Long> waits = new ArrayList<>();
        RateLimiterPolicy recording = new RateLimiterPolicy("test", limiter.config(), () -> now, waits::add);
        for (int i = 0; i < 10; i++) {
            assertThat(recording.tryAcquire()).isTrue();
        }
        for (int i = 0; i < 10; i++) {
            assertThat(recording.tryAcquire()).isTrue(); // the clock does not move: each one waits one more interval
        }
        assertThat(recording.tryAcquire()).as("the eleventh waiter would wait 1.1 s").isFalse();
        assertThat(waits).hasSize(10);
        for (int i = 0; i < 10; i++) {
            assertThat(waits.get(i)).isEqualTo((i + 1) * 100 * MS);
        }
    }

    @Test
    void anInterruptedWaitRejectsKeepsTheFlagAndGivesThePermitBack() {
        RateLimiterConfig config = RateLimiterConfig.builder().permits(1).period(Duration.ofSeconds(1))
                .maxWait(Duration.ofSeconds(2)).build();
        RateLimiterPolicy limiter = new RateLimiterPolicy("test", config, () -> now, nanos -> {
            throw new InterruptedException();
        });
        assertThat(limiter.tryAcquire()).isTrue();

        assertThat(limiter.tryAcquire()).isFalse();
        assertThat(Thread.interrupted()).isTrue();

        now += 1_000 * MS; // the given-back permit is due now, so no wait (the sleeper would throw again)
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void worksAcrossTheNanoTimeOverflow() {
        now = Long.MAX_VALUE - 150 * MS;
        RateLimiterPolicy limiter = limiter(2, Duration.ofMillis(200), Duration.ZERO);
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();

        now += 200 * MS; // wraps around to a negative value
        assertThat(now).isNegative();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
    }

    @Test
    void disabledLetsEverythingThrough() {
        RateLimiterConfig config = RateLimiterConfig.builder().enabled(false).permits(1).build();
        RateLimiterPolicy limiter = new RateLimiterPolicy("test", config, () -> now, nanos -> { });
        for (int i = 0; i < 100; i++) {
            assertThat(limiter.tryAcquire()).isTrue();
        }
        assertThat(limiter.availablePermits()).isEqualTo(1);
    }

    @Test
    void concurrentCallersNeverGetMorePermitsThanAvailable() throws InterruptedException {
        // The clock does not move, so exactly the burst may be let through, however the threads interleave.
        for (int round = 0; round < 20; round++) {
            RateLimiterPolicy limiter = new RateLimiterPolicy("test",
                    RateLimiterConfig.builder().permits(1_000).period(Duration.ofSeconds(1)).build(),
                    () -> 42L, nanos -> { });
            AtomicInteger accepted = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            List<Thread> workers = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                workers.add(Thread.ofPlatform().start(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                    for (int i = 0; i < 1_000; i++) {
                        if (limiter.tryAcquire()) {
                            accepted.incrementAndGet();
                        }
                    }
                }));
            }
            start.countDown();
            for (Thread worker : workers) {
                worker.join();
            }
            assertThat(accepted).as("round %d", round).hasValue(1_000);
        }
    }

    @Test
    void validatesTheConfiguration() {
        assertThatThrownBy(() -> RateLimiterConfig.builder().permits(0).build())
                .hasMessageContaining("permits must be at least 1");
        assertThatThrownBy(() -> RateLimiterConfig.builder().period(Duration.ZERO).build())
                .hasMessageContaining("period must be positive");
        assertThatThrownBy(() -> RateLimiterConfig.builder().permits(10).period(Duration.ofNanos(9)).build())
                .hasMessageContaining("at least one nanosecond per permit");
        assertThatThrownBy(() -> RateLimiterConfig.builder().maxWait(Duration.ofMillis(-1)).build())
                .hasMessageContaining("maxWait must not be negative");
    }
}
