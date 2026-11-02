package dev.resilience4cata.benchmark;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowSynchronizationStrategy;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Circuit breaker at realistic failure rates: one call in {@code failEvery} fails, 1 000 (0.1 %) or 100 (1 %).
 * {@link CircuitBreakerBenchmark} measures every call succeeding and one call in ten failing; this one measures the
 * rates in between, where most healthy services live. Run it with {@code -t 1} and {@code -t 4}: every thread
 * shares the same breaker.
 *
 * <p>Same configuration as {@link CircuitBreakerBenchmark}'s {@code *Mixed} benchmarks in both libraries: a
 * count-based window of 100 calls, evaluated after 100 calls, opening at 50 % failures, no event listeners, the
 * same preallocated stackless failure, so neither library pays for creating it. The rate stays far below the
 * threshold, so both breakers stay closed. Resilience4j is measured with both window strategies,
 * {@code SYNCHRONIZED} (its default) and {@code LOCK_FREE}.
 *
 * <p>Why it matters for resilience4cata: while the last 100 calls are all fast successes a call only reads one
 * field (the breaker is "quiet"); as soon as the window holds a failure, every call records its outcome in the
 * shared window. At 0.1 % the window holds a failure about a tenth of the time, at 1 % most of the time.
 *
 * <p>Difference in defaults: Resilience4j always times calls for slow-call detection; resilience4cata only does
 * when {@code slowCallDuration} is set, so it reads no clock here.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class CircuitBreakerFailureRateBenchmark {

    private GuardedService direct;
    private GuardedService resilience4cata;
    private Function<Boolean, String> resilience4jSynchronized;
    private Function<Boolean, String> resilience4jLockFree;

    /** Per-thread call counter: one call in {@code failEvery} fails. */
    @State(Scope.Thread)
    public static class Calls {
        /** One call in this many fails: 1 000 is 0.1 %, 100 is 1 %. */
        @Param({"1000", "100"})
        int failEvery;

        private int count;

        boolean nextFails() {
            if (++count == failEvery) {
                count = 0;
                return true;
            }
            return false;
        }
    }

    /** Builds both libraries with equivalent configuration. */
    @Setup
    public void setup() {
        direct = new GuardedService.Impl();

        ResilienceRegistry registry = ResilienceRegistry.builder()
                .circuitBreaker("bench", cb -> cb.windowSize(100).minimumCalls(100).failureRateThreshold(50)
                        .waitInOpen(Duration.ofSeconds(60)))
                .circuitBreaker("bench-time", cb -> cb.windowType(dev.resilience4cata.CircuitBreakerConfig.WindowType.TIME)
                        .windowDuration(Duration.ofSeconds(10)).minimumCalls(100).failureRateThreshold(50)
                        .waitInOpen(Duration.ofSeconds(60)))
                .build();
        resilience4cata = Resilient.of(GuardedService.class, new GuardedService.Impl(), registry);

        GuardedService.Impl impl = new GuardedService.Impl();
        resilience4jSynchronized = CircuitBreaker.decorateFunction(
                breaker("sync", SlidingWindowSynchronizationStrategy.SYNCHRONIZED), impl::maybeFail);
        resilience4jLockFree = CircuitBreaker.decorateFunction(
                breaker("lockfree", SlidingWindowSynchronizationStrategy.LOCK_FREE), impl::maybeFail);
    }

    private static CircuitBreaker breaker(String name, SlidingWindowSynchronizationStrategy strategy) {
        return CircuitBreaker.of(name, CircuitBreakerConfig.custom()
                .slidingWindow(100, 100, SlidingWindowType.COUNT_BASED, strategy)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(60))
                .build());
    }

    @Benchmark
    public String baseline(Calls calls) {
        try {
            return direct.maybeFail(calls.nextFails());
        } catch (RuntimeException e) {
            return "failed";
        }
    }

    @Benchmark
    public String resilience4cata(Calls calls) {
        try {
            return resilience4cata.maybeFail(calls.nextFails());
        } catch (RuntimeException e) {
            return "failed";
        }
    }

    @Benchmark
    public String resilience4jSynchronized(Calls calls) {
        try {
            return resilience4jSynchronized.apply(calls.nextFails());
        } catch (RuntimeException e) {
            return "failed";
        }
    }

    @Benchmark
    public String resilience4jLockFree(Calls calls) {
        try {
            return resilience4jLockFree.apply(calls.nextFails());
        } catch (RuntimeException e) {
            return "failed";
        }
    }
}
