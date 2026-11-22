package dev.resilience4cata.generated;

import dev.resilience4cata.RateLimiterConfig;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.jetbrains.lincheck.datastructures.Operation;
import org.junit.jupiter.api.Test;

/**
 * The GCRA rate limiter is linearizable: whatever several threads see while the clock moves, the same answers come
 * from some one-at-a-time order of the same calls. Lincheck generates the scenarios and, in model-checking mode,
 * explores their thread interleavings systematically (a failure prints the interleaving).
 */
public class RateLimiterLincheckTest {

    private final AtomicLong now = new AtomicLong(1_000);
    // 2 permits per 100 ns: an interval of 50 ns; calls may wait up to 60 ns for a permit.
    private final RateLimiterPolicy limiter = new RateLimiterPolicy("lincheck", RateLimiterConfig.builder()
            .permits(2).period(Duration.ofNanos(100)).maxWait(Duration.ofNanos(60)).build(), now::get, nanos -> {
            });

    @Operation
    public boolean tryAcquire() {
        return limiter.tryAcquire();
    }

    @Operation
    public long reserve() {
        return limiter.reserve();
    }

    @Operation
    public void advance() {
        now.addAndGet(30);
    }

    @Test
    void isLinearizable() {
        Lincheck.modelChecking().check(getClass());
    }
}
