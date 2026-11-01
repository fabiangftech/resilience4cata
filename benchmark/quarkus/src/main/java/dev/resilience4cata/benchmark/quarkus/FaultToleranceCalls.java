package dev.resilience4cata.benchmark.quarkus;

import io.smallrye.faulttolerance.api.CircuitBreakerName;
import io.smallrye.faulttolerance.api.RateLimit;
import jakarta.inject.Singleton;
import java.time.temporal.ChronoUnit;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;

/**
 * SmallRye Fault Tolerance, Quarkus's own fault tolerance (MicroProfile annotations): an interceptor on the bean.
 * {@code maxRetries = 2} is 3 attempts, as for the others. Its circuit breaker counts a rolling window of
 * {@code requestVolumeThreshold} calls and opens at {@code failureRatio}: the same as the others' count window of
 * 100 calls, evaluated after 100. It cannot be forced open, so {@link #open()} fails until the breaker opens
 * ({@link QuarkusBenchmark#setup()}), then stays open for the benchmark.
 */
@Singleton
public class FaultToleranceCalls {

    /** True only while the setup opens the breaker. */
    volatile boolean failing;

    @Retry(maxRetries = 2, delay = 0, jitter = 0)
    public String retry() {
        return "ok";
    }

    @Retry(maxRetries = 2, delay = 0, jitter = 0)
    public String retryOnce(Flip flip) {
        flip.attempt();
        return "ok";
    }

    @CircuitBreaker(requestVolumeThreshold = 100, failureRatio = 0.5, delay = 60, delayUnit = ChronoUnit.SECONDS)
    public String circuitBreaker() {
        return "ok";
    }

    @CircuitBreaker(requestVolumeThreshold = 100, failureRatio = 0.5, delay = 60, delayUnit = ChronoUnit.SECONDS)
    @CircuitBreakerName("open")
    public String open() {
        if (failing) {
            throw Flip.FAILURE;
        }
        return "ok";
    }

    @Bulkhead(1000)
    public String bulkhead() {
        return "ok";
    }

    @RateLimit(value = 1_000_000_000, window = 1, windowUnit = ChronoUnit.SECONDS)
    public String rateLimiter() {
        return "ok";
    }

    @Timeout(1000)
    public String timeout() {
        return "ok";
    }

    @Retry(maxRetries = 2, delay = 0, jitter = 0)
    @CircuitBreaker(requestVolumeThreshold = 100, failureRatio = 0.5, delay = 60, delayUnit = ChronoUnit.SECONDS)
    @RateLimit(value = 1_000_000_000, window = 1, windowUnit = ChronoUnit.SECONDS)
    @Bulkhead(1000)
    public String stack() {
        return "ok";
    }
}
