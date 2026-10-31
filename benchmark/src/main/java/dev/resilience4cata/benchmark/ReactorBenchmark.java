package dev.resilience4cata.benchmark;

import dev.resilience4cata.Pipeline;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.reactor.MonoPipelineOperator;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.reactor.timelimiter.TimeLimiterOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
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
import org.openjdk.jmh.infra.Blackhole;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Overhead of {@code Mono} methods on the success path: each benchmark builds the protected {@code Mono}
 * and subscribes to it, and the value reaches a {@code Blackhole}.
 *
 * <p>Resilience4j uses its Reactor operators (resilience4j-reactor 2.4.0) with
 * {@code transformDeferred}, as its documentation shows, and the same settings as {@link AsyncBenchmark}:
 * count window of 100 for the breaker, 3 attempts for the retry, 1 s for the time limiter, 1000
 * concurrent calls for the bulkhead, one billion permits per second for the rate limiter. {@code *Stack}
 * nests retry, circuit breaker, timeout and bulkhead in the same order in both libraries (Resilience4j:
 * the bulkhead operator applied first, the retry last). resilience4cata is measured as generated code
 * ({@link ReactorService}) and as a {@link Pipeline} applied with {@link MonoPipelineOperator}.
 *
 * <p>Two kinds of source: {@code Mono.just} (the value is there at subscription: a cache, a fallback),
 * and {@code *Pending}, a {@code Sinks.One} that the benchmark fills right after subscribing, so that
 * every layer must wait for it (an I/O call). The {@code baseline*} benchmarks subscribe to the same
 * sources without any policy. resilience4cata's timeout and hedge skip their timer for a value that is
 * there at subscription, which a value can no longer miss its deadline; Resilience4j's time limiter
 * ({@code Mono.timeout}) always arms one, on Reactor's parallel scheduler. The pending benchmarks
 * measure both libraries with a timer.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class ReactorBenchmark {

    private static final Mono<String> VALUE = Mono.just("ok");

    private ReactorService resilience4cata;
    private MonoPipelineOperator<String> pipelineStack;
    private UnaryOperator<Publisher<String>> circuitBreaker;
    private UnaryOperator<Publisher<String>> retry;
    private UnaryOperator<Publisher<String>> timeout;
    private UnaryOperator<Publisher<String>> bulkhead;
    private UnaryOperator<Publisher<String>> rateLimiter;
    private UnaryOperator<Publisher<String>> stackBulkhead;
    private UnaryOperator<Publisher<String>> stackTimeout;
    private UnaryOperator<Publisher<String>> stackCircuitBreaker;
    private UnaryOperator<Publisher<String>> stackRetry;

    /** Builds both libraries with equivalent configuration. */
    @Setup
    public void setup() {
        ResilienceRegistry registry = ResilienceRegistry.builder().build();
        resilience4cata = Resilient.of(ReactorService.class, new ReactorService.Impl(), registry);
        pipelineStack = MonoPipelineOperator.of(Pipeline.builder(registry, "pipeline-stack", String.class)
                .retry(r -> r.maxAttempts(3))
                .circuitBreaker()
                .timeout(t -> t.timeout(Duration.ofSeconds(1)))
                .bulkhead(b -> b.maxConcurrent(1000))
                .build());

        CircuitBreakerConfig breakerConfig = CircuitBreakerConfig.custom().slidingWindowSize(100).minimumNumberOfCalls(100).build();
        RetryConfig retryConfig = RetryConfig.custom().maxAttempts(3).build();
        TimeLimiterConfig timeLimiterConfig = TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(1)).build();
        BulkheadConfig bulkheadConfig = BulkheadConfig.custom().maxConcurrentCalls(1000).maxWaitDuration(Duration.ZERO).build();

        circuitBreaker = CircuitBreakerOperator.of(CircuitBreaker.of("cb", breakerConfig));
        retry = RetryOperator.of(Retry.of("retry", retryConfig));
        timeout = TimeLimiterOperator.of(TimeLimiter.of(timeLimiterConfig));
        bulkhead = BulkheadOperator.of(Bulkhead.of("bulkhead", bulkheadConfig));
        rateLimiter = RateLimiterOperator.of(RateLimiter.of("rl", RateLimiterConfig.custom()
                .limitForPeriod(1_000_000_000).limitRefreshPeriod(Duration.ofSeconds(1)).timeoutDuration(Duration.ZERO)
                .build()));
        stackBulkhead = BulkheadOperator.of(Bulkhead.of("stack", bulkheadConfig));
        stackTimeout = TimeLimiterOperator.of(TimeLimiter.of(timeLimiterConfig));
        stackCircuitBreaker = CircuitBreakerOperator.of(CircuitBreaker.of("stack", breakerConfig));
        stackRetry = RetryOperator.of(Retry.of("stack", retryConfig));
    }

    private static Mono<String> r4j(Mono<String> source, UnaryOperator<Publisher<String>> operator) {
        return source.transformDeferred(operator);
    }

    private Mono<String> r4jStack(Mono<String> source) {
        return r4j(r4j(r4j(r4j(source, stackBulkhead), stackTimeout), stackCircuitBreaker), stackRetry);
    }

    /** Subscribes, then fills the sink: every layer waits for the value. */
    private static void pending(Sinks.One<String> sink, Mono<String> mono, Blackhole blackhole) {
        mono.subscribe(blackhole::consume);
        sink.tryEmitValue("ok");
    }

    // ---- the value is there at subscription

    @Benchmark
    public void baseline(Blackhole blackhole) {
        VALUE.subscribe(blackhole::consume);
    }

    @Benchmark
    public void resilience4cataCircuitBreaker(Blackhole blackhole) {
        resilience4cata.circuitBreaker(VALUE).subscribe(blackhole::consume);
    }

    @Benchmark
    public void resilience4jCircuitBreaker(Blackhole blackhole) {
        r4j(VALUE, circuitBreaker).subscribe(blackhole::consume);
    }

    @Benchmark
    public void resilience4cataRetry(Blackhole blackhole) {
        resilience4cata.retry(VALUE).subscribe(blackhole::consume);
    }

    @Benchmark
    public void resilience4jRetry(Blackhole blackhole) {
        r4j(VALUE, retry).subscribe(blackhole::consume);
    }

    @Benchmark
    public void resilience4cataTimeout(Blackhole blackhole) {
        resilience4cata.timeout(VALUE).subscribe(blackhole::consume);
    }

    @Benchmark
    public void resilience4jTimeout(Blackhole blackhole) {
        r4j(VALUE, timeout).subscribe(blackhole::consume);
    }

    @Benchmark
    public void resilience4cataBulkhead(Blackhole blackhole) {
        resilience4cata.bulkhead(VALUE).subscribe(blackhole::consume);
    }

    @Benchmark
    public void resilience4jBulkhead(Blackhole blackhole) {
        r4j(VALUE, bulkhead).subscribe(blackhole::consume);
    }

    @Benchmark
    public void resilience4cataRateLimiter(Blackhole blackhole) {
        resilience4cata.rateLimiter(VALUE).subscribe(blackhole::consume);
    }

    @Benchmark
    public void resilience4jRateLimiter(Blackhole blackhole) {
        r4j(VALUE, rateLimiter).subscribe(blackhole::consume);
    }

    @Benchmark
    public void resilience4cataStack(Blackhole blackhole) {
        resilience4cata.stack(VALUE).subscribe(blackhole::consume);
    }

    @Benchmark
    public void resilience4cataPipelineStack(Blackhole blackhole) {
        VALUE.transform(pipelineStack).subscribe(blackhole::consume);
    }

    @Benchmark
    public void resilience4jStack(Blackhole blackhole) {
        r4jStack(VALUE).subscribe(blackhole::consume);
    }

    // ---- the value comes after the subscription

    @Benchmark
    public void baselinePending(Blackhole blackhole) {
        Sinks.One<String> sink = Sinks.one();
        pending(sink, sink.asMono(), blackhole);
    }

    @Benchmark
    public void resilience4cataTimeoutPending(Blackhole blackhole) {
        Sinks.One<String> sink = Sinks.one();
        pending(sink, resilience4cata.timeout(sink.asMono()), blackhole);
    }

    @Benchmark
    public void resilience4jTimeoutPending(Blackhole blackhole) {
        Sinks.One<String> sink = Sinks.one();
        pending(sink, r4j(sink.asMono(), timeout), blackhole);
    }

    @Benchmark
    public void resilience4cataStackPending(Blackhole blackhole) {
        Sinks.One<String> sink = Sinks.one();
        pending(sink, resilience4cata.stack(sink.asMono()), blackhole);
    }

    @Benchmark
    public void resilience4jStackPending(Blackhole blackhole) {
        Sinks.One<String> sink = Sinks.one();
        pending(sink, r4jStack(sink.asMono()), blackhole);
    }
}
