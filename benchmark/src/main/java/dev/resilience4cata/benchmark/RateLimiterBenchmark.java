package dev.resilience4cata.benchmark;

import dev.resilience4cata.RateLimitExceededException;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Rate limiter overhead. Run with {@code -t 1} and {@code -t 4}: every thread shares the same limiter.
 *
 * <ul>
 *   <li>{@code *Free}: one billion permits per second, never reached, so every call is let through.</li>
 *   <li>{@code *Exhausted}: one permit per hour, taken by the setup, so every call is rejected.</li>
 * </ul>
 *
 * <p>Neither library waits ({@code maxWait} / {@code timeoutDuration} of zero). Resilience4j uses its
 * default {@code AtomicRateLimiter}, with stack traces disabled on its rejection exception
 * ({@code writableStackTraceEnabled(false)}), which is fair to it: resilience4cata's rejection
 * exception has none either. Both libraries read {@code System.nanoTime()} once per call.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class RateLimiterBenchmark {

    private static final int UNREACHABLE = 1_000_000_000;

    private ThrottledService direct;
    private ThrottledService resilience4cata;
    private Supplier<String> free;
    private Supplier<String> exhausted;

    /** Builds both libraries with equivalent configuration and empties the {@code *Exhausted} limiters. */
    @Setup
    public void setup() {
        direct = new ThrottledService.Impl();
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .rateLimiter("bench", r -> r.permits(UNREACHABLE).period(Duration.ofSeconds(1)))
                .rateLimiter("bench-exhausted", r -> r.permits(1).period(Duration.ofHours(1)))
                .build();
        resilience4cata = Resilient.of(ThrottledService.class, new ThrottledService.Impl(), registry);
        if (!registry.rateLimiterPolicy("setup", "exhausted", "bench-exhausted", null).tryAcquire()) {
            throw new IllegalStateException("could not take the only permit");
        }

        ThrottledService.Impl impl = new ThrottledService.Impl();
        free = RateLimiter.decorateSupplier(limiter("free", UNREACHABLE, Duration.ofSeconds(1)), impl::call);
        RateLimiter exhaustedLimiter = limiter("exhausted", 1, Duration.ofHours(1));
        if (!exhaustedLimiter.acquirePermission()) {
            throw new IllegalStateException("could not take the only permit");
        }
        exhausted = RateLimiter.decorateSupplier(exhaustedLimiter, impl::callExhausted);
    }

    private static RateLimiter limiter(String name, int permits, Duration period) {
        return RateLimiter.of(name, RateLimiterConfig.custom()
                .limitForPeriod(permits)
                .limitRefreshPeriod(period)
                .timeoutDuration(Duration.ZERO)
                .writableStackTraceEnabled(false)
                .build());
    }

    @Benchmark
    public String baseline() {
        return direct.call();
    }

    @Benchmark
    public String resilience4cataFree() {
        return resilience4cata.call();
    }

    @Benchmark
    public String resilience4jFree() {
        return free.get();
    }

    @Benchmark
    public String resilience4cataExhausted() {
        try {
            return resilience4cata.callExhausted();
        } catch (RateLimitExceededException e) {
            return "rejected";
        }
    }

    @Benchmark
    public String resilience4jExhausted() {
        try {
            return exhausted.get();
        } catch (RequestNotPermitted e) {
            return "rejected";
        }
    }
}
