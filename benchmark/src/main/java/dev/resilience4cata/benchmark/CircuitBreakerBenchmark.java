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
 * </ul>
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
                .build();
        resilience4cata = Resilient.of(GuardedService.class, new GuardedService.Impl(), registry);

        GuardedService.Impl impl = new GuardedService.Impl();
        synchronizedSuccess = CircuitBreaker.decorateSupplier(breaker("sync-success", SlidingWindowSynchronizationStrategy.SYNCHRONIZED), impl::call);
        lockFreeSuccess = CircuitBreaker.decorateSupplier(breaker("lockfree-success", SlidingWindowSynchronizationStrategy.LOCK_FREE), impl::call);
        synchronizedMixed = CircuitBreaker.decorateFunction(breaker("sync-mixed", SlidingWindowSynchronizationStrategy.SYNCHRONIZED), impl::maybeFail);
        lockFreeMixed = CircuitBreaker.decorateFunction(breaker("lockfree-mixed", SlidingWindowSynchronizationStrategy.LOCK_FREE), impl::maybeFail);
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
}
