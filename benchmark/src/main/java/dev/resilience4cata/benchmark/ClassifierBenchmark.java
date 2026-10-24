package dev.resilience4cata.benchmark;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowSynchronizationStrategy;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
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
 * Cost of result classification on the success path: every call returns 200, which the classifier
 * accepts. Run it with {@code -t 1} and {@code -t 4}.
 *
 * <ul>
 *   <li>{@code resilience4cataPlain}: a breaker without a classifier, the reference.</li>
 *   <li>{@code resilience4cataClassified}: the same breaker with a classifier; the generated code calls
 *       {@code classify} once (a monomorphic call on a {@code final} field) and {@code onResult}.</li>
 *   <li>{@code resilience4cataRetriedAndClassified}: retry plus breaker, both using the classifier (one
 *       shared instance, so {@code classify} runs twice per call: once per layer).</li>
 *   <li>{@code resilience4j*}: the equivalent Resilience4j configuration, with the classification as a
 *       {@code recordResult} predicate on the breaker and a {@code retryOnResult} predicate on the retry.</li>
 * </ul>
 *
 * <p>Configuration on both sides: a count-based window of 100 calls, evaluated after 100 calls, opening
 * at 50% failures, no event listeners; the retry makes at most 3 attempts. Resilience4j uses its default
 * {@code SYNCHRONIZED} window strategy. The retry's backoff is irrelevant here, since no call is retried.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class ClassifierBenchmark {

    private ClassifiedService resilience4cata;
    private Supplier<Integer> resilience4jClassified;
    private Supplier<Integer> resilience4jRetriedAndClassified;

    /** Builds both libraries with equivalent configuration. */
    @Setup
    public void setup() {
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .circuitBreaker("bench", cb -> cb.windowSize(100).minimumCalls(100).failureRateThreshold(50)
                        .waitInOpen(Duration.ofSeconds(60)))
                .retry("bench", retry -> retry.maxAttempts(3))
                .build();
        resilience4cata = Resilient.of(ClassifiedService.class, new ClassifiedService.Impl(), registry);

        ClassifiedService.Impl impl = new ClassifiedService.Impl();
        resilience4jClassified = CircuitBreaker.decorateSupplier(breaker("classified"), impl::classified);
        Retry retry = Retry.of("retried", RetryConfig.<Integer>custom()
                .maxAttempts(3)
                .retryOnResult(status -> status >= 500)
                .build());
        resilience4jRetriedAndClassified = Retry.decorateSupplier(retry,
                CircuitBreaker.decorateSupplier(breaker("retried"), impl::retriedAndClassified));
    }

    private static CircuitBreaker breaker(String name) {
        return CircuitBreaker.of(name, CircuitBreakerConfig.custom()
                .slidingWindow(100, 100, SlidingWindowType.COUNT_BASED, SlidingWindowSynchronizationStrategy.SYNCHRONIZED)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(60))
                .recordResult(result -> result instanceof Integer status && status >= 500)
                .build());
    }

    @Benchmark
    public Integer resilience4cataPlain() {
        return resilience4cata.plain();
    }

    @Benchmark
    public Integer resilience4cataClassified() {
        return resilience4cata.classified();
    }

    @Benchmark
    public Integer resilience4cataRetriedAndClassified() {
        return resilience4cata.retriedAndClassified();
    }

    @Benchmark
    public Integer resilience4jClassified() {
        return resilience4jClassified.get();
    }

    @Benchmark
    public Integer resilience4jRetriedAndClassified() {
        return resilience4jRetriedAndClassified.get();
    }
}
