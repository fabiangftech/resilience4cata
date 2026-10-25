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
 * Circuit breaker overhead. Run it with several thread counts ({@code -t 1}, {@code -t 4}): every
 * thread shares the same breaker, which is where locking shows up.
 *
 * <p>Both libraries use a count-based window of 100 calls, evaluated after 100 calls, opening at 50%
 * failures, no event listeners. Resilience4j is measured with both of its window strategies:
 * {@code SYNCHRONIZED} (its default) and {@code LOCK_FREE}.
 *
 * <ul>
 *   <li>{@code *Success}: every call succeeds.</li>
 *   <li>{@code *Mixed}: one call in ten fails. The rate stays under the threshold, so the breaker
 *       stays closed while its window records failures.</li>
 *   <li>{@code *Open}: the breaker is forced open in the setup, so every call is rejected. Both
 *       libraries throw without filling a stack trace (Resilience4j:
 *       {@code writableStackTraceEnabled(false)}, which is fair to it: resilience4cata's rejection
 *       exception is preallocated and stackless).</li>
 * </ul>
 *
 * <p>{@code *Time*} benchmarks use a time-based window of 10 seconds instead (Resilience4j:
 * {@code TIME_BASED}, its default {@code SYNCHRONIZED} strategy), with the same thresholds.
 *
 * <p>Difference in defaults: Resilience4j always times calls for slow-call detection; resilience4cata
 * only does when {@code slowCallDuration} is set, so it reads no clock here.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class CircuitBreakerBenchmark {

    private GuardedService direct;
    private GuardedService resilience4cata;
    private Supplier<String> synchronizedSuccess;
    private Supplier<String> lockFreeSuccess;
    private Function<Boolean, String> synchronizedMixed;
    private Function<Boolean, String> lockFreeMixed;
    private Supplier<String> timeSuccess;
    private Function<Boolean, String> timeMixed;
    private Supplier<String> open;

    /** Per-thread call counter, used to fail one call in ten. */
    @State(Scope.Thread)
    public static class Calls {
        private int count;

        boolean nextFails() {
            return ++count % 10 == 0;
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
                        .windowSize(10).minimumCalls(100).failureRateThreshold(50).waitInOpen(Duration.ofSeconds(60)))
                .build();
        resilience4cata = Resilient.of(GuardedService.class, new GuardedService.Impl(), registry);
        registry.circuitBreaker("bench-open").forceOpen();

        GuardedService.Impl impl = new GuardedService.Impl();
        synchronizedSuccess = CircuitBreaker.decorateSupplier(breaker("sync-success", SlidingWindowSynchronizationStrategy.SYNCHRONIZED), impl::call);
        lockFreeSuccess = CircuitBreaker.decorateSupplier(breaker("lockfree-success", SlidingWindowSynchronizationStrategy.LOCK_FREE), impl::call);
        synchronizedMixed = CircuitBreaker.decorateFunction(breaker("sync-mixed", SlidingWindowSynchronizationStrategy.SYNCHRONIZED), impl::maybeFail);
        lockFreeMixed = CircuitBreaker.decorateFunction(breaker("lockfree-mixed", SlidingWindowSynchronizationStrategy.LOCK_FREE), impl::maybeFail);
        timeSuccess = CircuitBreaker.decorateSupplier(timeBreaker("time-success"), impl::call);
        timeMixed = CircuitBreaker.decorateFunction(timeBreaker("time-mixed"), impl::maybeFail);
        CircuitBreaker forcedOpen = CircuitBreaker.of("open", CircuitBreakerConfig.custom()
                .slidingWindow(100, 100, SlidingWindowType.COUNT_BASED, SlidingWindowSynchronizationStrategy.SYNCHRONIZED)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(60))
                .writableStackTraceEnabled(false)
                .build());
        forcedOpen.transitionToForcedOpenState();
        open = CircuitBreaker.decorateSupplier(forcedOpen, impl::call);
    }

    private static CircuitBreaker timeBreaker(String name) {
        return CircuitBreaker.of(name, CircuitBreakerConfig.custom()
                .slidingWindow(10, 100, SlidingWindowType.TIME_BASED, SlidingWindowSynchronizationStrategy.SYNCHRONIZED)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(60))
                .build());
    }

    private static CircuitBreaker breaker(String name, SlidingWindowSynchronizationStrategy strategy) {
        return CircuitBreaker.of(name, CircuitBreakerConfig.custom()
                .slidingWindow(100, 100, SlidingWindowType.COUNT_BASED, strategy)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(60))
                .build());
    }

    @Benchmark
    public String baselineSuccess() {
        return direct.call();
    }

    @Benchmark
    public String resilience4cataSuccess() {
        return resilience4cata.call();
    }

    @Benchmark
    public String resilience4jSynchronizedSuccess() {
        return synchronizedSuccess.get();
    }

    @Benchmark
    public String resilience4jLockFreeSuccess() {
        return lockFreeSuccess.get();
    }

    @Benchmark
    public String baselineMixed(Calls calls) {
        try {
            return direct.maybeFail(calls.nextFails());
        } catch (RuntimeException e) {
            return "failed";
        }
    }

    @Benchmark
    public String resilience4cataMixed(Calls calls) {
        try {
            return resilience4cata.maybeFail(calls.nextFails());
        } catch (RuntimeException e) {
            return "failed";
        }
    }

    @Benchmark
    public String resilience4jSynchronizedMixed(Calls calls) {
        try {
            return synchronizedMixed.apply(calls.nextFails());
        } catch (RuntimeException e) {
            return "failed";
        }
    }

    @Benchmark
    public String resilience4jLockFreeMixed(Calls calls) {
        try {
            return lockFreeMixed.apply(calls.nextFails());
        } catch (RuntimeException e) {
            return "failed";
        }
    }

    @Benchmark
    public String resilience4cataTimeSuccess() {
        return resilience4cata.callTime();
    }

    @Benchmark
    public String resilience4jTimeSuccess() {
        return timeSuccess.get();
    }

    @Benchmark
    public String resilience4cataTimeMixed(Calls calls) {
        try {
            return resilience4cata.maybeFailTime(calls.nextFails());
        } catch (RuntimeException e) {
            return "failed";
        }
    }

    @Benchmark
    public String resilience4jTimeMixed(Calls calls) {
        try {
            return timeMixed.apply(calls.nextFails());
        } catch (RuntimeException e) {
            return "failed";
        }
    }

    @Benchmark
    public String resilience4cataOpen() {
        try {
            return resilience4cata.callOpen();
        } catch (dev.resilience4cata.CallNotPermittedException e) {
            return "rejected";
        }
    }

    @Benchmark
    public String resilience4jOpen() {
        try {
            return open.get();
        } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
            return "rejected";
        }
    }
}
