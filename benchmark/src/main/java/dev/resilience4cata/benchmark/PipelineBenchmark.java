package dev.resilience4cata.benchmark;

import dev.resilience4cata.Pipeline;
import dev.resilience4cata.PipelineCall;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.RetryConfig;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowSynchronizationStrategy;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.Retry;
import java.time.Duration;
import java.util.List;
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
 * Overhead of the programmatic {@link Pipeline} against generated code and Resilience4j, success path.
 * Run it with {@code -prof gc}: the pipeline and generated benchmarks should show 0 B/op (except
 * {@code *Capturing}, see below), and with {@code -t 1} and {@code -t 4}.
 *
 * <p>Configuration, the same in the three libraries: retry with 3 attempts, no delay, no jitter;
 * count-based breaker window of 100 calls, evaluated after 100 calls, opening at 50% failures
 * (Resilience4j: {@code LOCK_FREE} window strategy, the faster of its two); rate limiter with
 * 1,000,000,000 permits per second and no wait; bulkhead with 100 concurrent calls and no wait
 * (Resilience4j: semaphore bulkhead, {@code maxWaitDuration = 0}); no event listeners.
 *
 * <ul>
 *   <li>{@code *RetryCircuitBreaker}: retry around a circuit breaker.</li>
 *   <li>{@code *Four}: retry, circuit breaker, rate limiter, bulkhead (outermost first).</li>
 *   <li>{@code *All}: the six policies including timeout (1 s) and a fallback. Resilience4j has no
 *       synchronous timeout on the caller's thread ({@code TimeLimiter} needs a future), so it is not
 *       measured here.</li>
 *   <li>{@code *Capturing}: retry and breaker, with a lambda that captures an argument on every call,
 *       which is how a pipeline is usually called ({@code pipeline.execute(() -> client.charge(order))}).
 *       Whether the lambda is allocated depends on escape analysis inlining {@code execute}; {@code -prof
 *       gc} shows it. Resilience4j is called the same way: {@code retry.executeSupplier(() ->
 *       breaker.executeSupplier(() -> service.echo(argument)))}.</li>
 *   <li>{@code pipelinePolluted}: the same pipeline as {@code pipelineRetryCircuitBreaker}, measured
 *       after five other pipelines with different policy sets and calls have run through the same
 *       {@code Pipeline} code, as in an application with many pipelines. This is the case that generated
 *       code avoids by being per method: the call site inside the pipeline becomes megamorphic.</li>
 * </ul>
 *
 * <p>Resilience4j is composed by hand, the way its {@code Decorators} class does it
 * ({@code Decorators.ofSupplier(s).withBulkhead(b).withRateLimiter(r).withCircuitBreaker(c).withRetry(retry)}
 * nests the suppliers in the same order); {@code resilience4j-all}, which holds {@code Decorators}, is
 * not a dependency of this module. The decorated suppliers are built once, in setup.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class PipelineBenchmark {

    private PipelineService.Impl direct;
    private PipelineService generated;
    private PipelineService.Impl forPipeline;
    private Pipeline<String> pipelineRetryCircuitBreaker;
    private Pipeline<String> pipelineFour;
    private Pipeline<String> pipelineAll;
    private PipelineCall<String, RuntimeException> pipelineCall;
    private Retry retry;
    private CircuitBreaker breaker;
    private PipelineService.Impl forResilience4j;
    private Supplier<String> resilience4jRetryCircuitBreaker;
    private Supplier<String> resilience4jFour;
    private String argument;

    /** Registers the same configuration for generated code and pipelines. */
    static ResilienceRegistry registry() {
        return ResilienceRegistry.builder()
                .retry("bench", r -> r.maxAttempts(3).delay(Duration.ZERO)
                        .backoff(RetryConfig.Backoff.FIXED).jitter(RetryConfig.Jitter.NONE))
                .circuitBreaker("bench", cb -> cb.windowSize(100).minimumCalls(100).failureRateThreshold(50)
                        .waitInOpen(Duration.ofSeconds(60)))
                .rateLimiter("bench", rl -> rl.permits(1_000_000_000).period(Duration.ofSeconds(1)))
                .timeout("bench", t -> t.timeout(Duration.ofSeconds(1)))
                .bulkhead("bench", b -> b.maxConcurrent(100))
                .build();
    }

    static Pipeline<String> retryCircuitBreaker(ResilienceRegistry registry, String id) {
        return Pipeline.builder(registry, id, String.class).retry("bench").circuitBreaker("bench").build();
    }

    /** Builds the three libraries with equivalent configuration. */
    @Setup
    public void setup() {
        direct = new PipelineService.Impl();
        argument = "ok";

        ResilienceRegistry registry = registry();
        generated = Resilient.of(PipelineService.class, new PipelineService.Impl(), registry);
        forPipeline = new PipelineService.Impl();
        pipelineRetryCircuitBreaker = retryCircuitBreaker(registry, "retry-circuit-breaker");
        pipelineFour = Pipeline.builder(registry, "four", String.class)
                .retry("bench").circuitBreaker("bench").rateLimiter("bench").bulkhead("bench").build();
        pipelineAll = Pipeline.builder(registry, "all", String.class)
                .retry("bench").circuitBreaker("bench").rateLimiter("bench").timeout("bench").bulkhead("bench")
                .fallbackHandler(new PipelineService.Constant())
                .build();
        PipelineService.Impl target = forPipeline;
        pipelineCall = target::call;

        retry = Retry.of("bench", io.github.resilience4j.retry.RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ZERO)
                .retryExceptions(Exception.class)
                .build());
        breaker = CircuitBreaker.of("bench", CircuitBreakerConfig.custom()
                .slidingWindow(100, 100, SlidingWindowType.COUNT_BASED, SlidingWindowSynchronizationStrategy.LOCK_FREE)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(60))
                .build());
        RateLimiter rateLimiter = RateLimiter.of("bench", RateLimiterConfig.custom()
                .limitForPeriod(1_000_000_000)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ZERO)
                .build());
        Bulkhead bulkhead = Bulkhead.of("bench", BulkheadConfig.custom()
                .maxConcurrentCalls(100)
                .maxWaitDuration(Duration.ZERO)
                .build());
        forResilience4j = new PipelineService.Impl();
        resilience4jRetryCircuitBreaker = Retry.decorateSupplier(retry,
                CircuitBreaker.decorateSupplier(breaker, forResilience4j::call));
        resilience4jFour = Retry.decorateSupplier(retry,
                CircuitBreaker.decorateSupplier(breaker,
                        RateLimiter.decorateSupplier(rateLimiter,
                                Bulkhead.decorateSupplier(bulkhead, forResilience4j::call))));
    }

    @Benchmark
    public String baseline() {
        return direct.call();
    }

    @Benchmark
    public String generatedRetryCircuitBreaker() {
        return generated.retryCircuitBreaker();
    }

    @Benchmark
    public String pipelineRetryCircuitBreaker() {
        return pipelineRetryCircuitBreaker.execute(pipelineCall);
    }

    @Benchmark
    public String resilience4jRetryCircuitBreaker() {
        return resilience4jRetryCircuitBreaker.get();
    }

    @Benchmark
    public String generatedFour() {
        return generated.four();
    }

    @Benchmark
    public String pipelineFour() {
        return pipelineFour.execute(pipelineCall);
    }

    @Benchmark
    public String resilience4jFour() {
        return resilience4jFour.get();
    }

    @Benchmark
    public String generatedAll() {
        return generated.all();
    }

    @Benchmark
    public String pipelineAll() {
        return pipelineAll.execute(pipelineCall);
    }

    @Benchmark
    public String generatedCapturing() {
        return generated.echo(argument);
    }

    @Benchmark
    public String pipelineCapturing() {
        PipelineService.Impl target = forPipeline;
        String value = argument;
        return pipelineRetryCircuitBreaker.execute(() -> target.echo(value));
    }

    @Benchmark
    public String resilience4jCapturing() {
        PipelineService.Impl target = forResilience4j;
        String value = argument;
        CircuitBreaker breaker = this.breaker;
        return retry.executeSupplier(() -> breaker.executeSupplier(() -> target.echo(value)));
    }

    /** A pipeline measured after other pipelines and calls have gone through the same code. */
    @State(Scope.Benchmark)
    public static class Polluted {
        private Pipeline<String> pipeline;
        private PipelineCall<String, RuntimeException> call;

        /** Runs five other pipelines, with different policies and call classes, before measuring. */
        @Setup
        public void setup() {
            ResilienceRegistry registry = registry();
            PipelineService.Impl target = new PipelineService.Impl();
            List<Pipeline<String>> others = List.of(
                    Pipeline.builder(registry, "other-1", String.class).retry("bench").build(),
                    Pipeline.builder(registry, "other-2", String.class).circuitBreaker("bench").build(),
                    Pipeline.builder(registry, "other-3", String.class).bulkhead("bench").rateLimiter("bench").build(),
                    Pipeline.builder(registry, "other-4", String.class).retry("bench").bulkhead("bench").build(),
                    retryCircuitBreaker(registry, "other-5"));
            List<PipelineCall<String, RuntimeException>> calls = List.of(
                    target::retryCircuitBreaker, target::four, target::all, () -> target.echo("x"), target::call);
            for (int round = 0; round < 20_000; round++) {
                for (int i = 0; i < others.size(); i++) {
                    others.get(i).execute(calls.get(i));
                }
            }
            pipeline = retryCircuitBreaker(registry, "polluted");
            call = target::call;
        }
    }

    @Benchmark
    public String pipelinePolluted(Polluted polluted) {
        return polluted.pipeline.execute(polluted.call);
    }
}
