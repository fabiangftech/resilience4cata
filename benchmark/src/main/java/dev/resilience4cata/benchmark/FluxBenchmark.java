package dev.resilience4cata.benchmark;

import dev.resilience4cata.Pipeline;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.reactor.FluxPipelineOperator;
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
import java.util.stream.IntStream;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Overhead of {@code Flux} methods on the success path: each benchmark builds the protected {@code Flux},
 * subscribes to it, and every element reaches a {@code Blackhole}. One operation is one whole stream.
 *
 * <p>Resilience4j uses its Reactor operators (resilience4j-reactor 2.4.0) with {@code transformDeferred} and
 * the same settings as {@link ReactorBenchmark}: count window of 100 for the breaker, 3 attempts for the
 * retry, 1 s for the time limiter, 1000 concurrent calls for the bulkhead, one billion permits per second
 * for the rate limiter; {@code *Stack} nests retry, circuit breaker, timeout and bulkhead in the same order
 * in both libraries. On a {@code Flux}, Resilience4j's time limiter is Reactor's {@code flux.timeout}, a
 * deadline restarted by every element like resilience4cata's {@code @Timeout} on a {@code Flux}. Two
 * differences of meaning that the success path does not exercise: Resilience4j's retry subscribes again
 * after a failure even when elements were emitted (resilience4cata only before the first element), and
 * its breaker times a stream to its end (resilience4cata to its first element; neither benchmark sets a
 * slow-call duration, so nothing is timed). resilience4cata is measured as generated code
 * ({@link FluxService}) and as a {@link Pipeline} applied with {@link FluxPipelineOperator}.
 *
 * <p>Three kinds of source: ten elements there at subscription ({@code Flux.fromArray}); {@code *Pending},
 * ten elements that the benchmark emits into a {@code Sinks.Many} right after subscribing, so that every
 * layer waits for them and both timeouts arm a timer; and {@code *Long}, a thousand elements there at
 * subscription, where the cost per element shows. The {@code baseline*} benchmarks subscribe to the same
 * sources without any policy.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class FluxBenchmark {

    private static final String[] TEN_ELEMENTS = IntStream.range(0, 10).mapToObj(i -> "e" + i).toArray(String[]::new);
    private static final Flux<String> TEN = Flux.fromArray(TEN_ELEMENTS);
    private static final Flux<String> THOUSAND = Flux.fromArray(
            IntStream.range(0, 1000).mapToObj(i -> "e" + i).toArray(String[]::new));

    private FluxService resilience4cata;
    private FluxPipelineOperator<String> pipelineStack;
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
        resilience4cata = Resilient.of(FluxService.class, new FluxService.Impl(), registry);
        pipelineStack = FluxPipelineOperator.of(Pipeline.builder(registry, "pipeline-stack", String.class)
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

    private static Flux<String> r4j(Flux<String> source, UnaryOperator<Publisher<String>> operator) {
        return source.transformDeferred(operator);
    }

    private Flux<String> r4jStack(Flux<String> source) {
        return r4j(r4j(r4j(r4j(source, stackBulkhead), stackTimeout), stackCircuitBreaker), stackRetry);
    }

    /** Subscribes, then emits ten elements and completes: every layer waits for them. */
    private static void pending(Sinks.Many<String> sink, Flux<String> flux, Blackhole blackhole) {
        flux.subscribe(blackhole::consume);
        for (String element : TEN_ELEMENTS) {
            sink.tryEmitNext(element);
        }
        sink.tryEmitComplete();
    }

    private static Sinks.Many<String> sink() {
        return Sinks.many().unicast().onBackpressureBuffer();
    }

    // ---- ten elements there at subscription

    @Benchmark
    public void baseline(Blackhole blackhole) {
        TEN.subscribe(blackhole::consume);
    }

    @Benchmark
    public void resilience4cataCircuitBreaker(Blackhole blackhole) {
        resilience4cata.circuitBreaker(TEN).subscribe(blackhole::consume);
    }

    @Benchmark
    public void resilience4jCircuitBreaker(Blackhole blackhole) {
        r4j(TEN, circuitBreaker).subscribe(blackhole::consume);
    }

    @Benchmark
    public void resilience4cataRetry(Blackhole blackhole) {
        resilience4cata.retry(TEN).subscribe(blackhole::consume);
    }

    @Benchmark
    public void resilience4jRetry(Blackhole blackhole) {
        r4j(TEN, retry).subscribe(blackhole::consume);
    }

    @Benchmark
    public void resilience4cataTimeout(Blackhole blackhole) {
        resilience4cata.timeout(TEN).subscribe(blackhole::consume);
    }

    @Benchmark
    public void resilience4jTimeout(Blackhole blackhole) {
        r4j(TEN, timeout).subscribe(blackhole::consume);
    }

    @Benchmark
    public void resilience4cataBulkhead(Blackhole blackhole) {
        resilience4cata.bulkhead(TEN).subscribe(blackhole::consume);
    }

    @Benchmark
    public void resilience4jBulkhead(Blackhole blackhole) {
        r4j(TEN, bulkhead).subscribe(blackhole::consume);
    }

    @Benchmark
    public void resilience4cataRateLimiter(Blackhole blackhole) {
        resilience4cata.rateLimiter(TEN).subscribe(blackhole::consume);
    }

    @Benchmark
    public void resilience4jRateLimiter(Blackhole blackhole) {
        r4j(TEN, rateLimiter).subscribe(blackhole::consume);
    }

    @Benchmark
    public void resilience4cataStack(Blackhole blackhole) {
        resilience4cata.stack(TEN).subscribe(blackhole::consume);
    }

    @Benchmark
    public void resilience4cataPipelineStack(Blackhole blackhole) {
        TEN.transform(pipelineStack).subscribe(blackhole::consume);
    }

    @Benchmark
    public void resilience4jStack(Blackhole blackhole) {
        r4jStack(TEN).subscribe(blackhole::consume);
    }

    // ---- ten elements after the subscription

    @Benchmark
    public void baselinePending(Blackhole blackhole) {
        Sinks.Many<String> sink = sink();
        pending(sink, sink.asFlux(), blackhole);
    }

    @Benchmark
    public void resilience4cataTimeoutPending(Blackhole blackhole) {
        Sinks.Many<String> sink = sink();
        pending(sink, resilience4cata.timeout(sink.asFlux()), blackhole);
    }

    @Benchmark
    public void resilience4jTimeoutPending(Blackhole blackhole) {
        Sinks.Many<String> sink = sink();
        pending(sink, r4j(sink.asFlux(), timeout), blackhole);
    }

    @Benchmark
    public void resilience4cataStackPending(Blackhole blackhole) {
        Sinks.Many<String> sink = sink();
        pending(sink, resilience4cata.stack(sink.asFlux()), blackhole);
    }

    @Benchmark
    public void resilience4jStackPending(Blackhole blackhole) {
        Sinks.Many<String> sink = sink();
        pending(sink, r4jStack(sink.asFlux()), blackhole);
    }

    // ---- a thousand elements: the cost per element

    @Benchmark
    public void baselineLong(Blackhole blackhole) {
        THOUSAND.subscribe(blackhole::consume);
    }

    @Benchmark
    public void resilience4cataTimeoutLong(Blackhole blackhole) {
        resilience4cata.timeout(THOUSAND).subscribe(blackhole::consume);
    }

    @Benchmark
    public void resilience4jTimeoutLong(Blackhole blackhole) {
        r4j(THOUSAND, timeout).subscribe(blackhole::consume);
    }

    @Benchmark
    public void resilience4cataStackLong(Blackhole blackhole) {
        resilience4cata.stack(THOUSAND).subscribe(blackhole::consume);
    }

    @Benchmark
    public void resilience4jStackLong(Blackhole blackhole) {
        r4jStack(THOUSAND).subscribe(blackhole::consume);
    }
}
