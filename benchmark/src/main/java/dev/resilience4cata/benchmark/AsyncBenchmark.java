package dev.resilience4cata.benchmark;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Overhead of asynchronous methods on the success path: the delegate returns an already completed
 * future, and each benchmark returns the stage the caller gets, without waiting on it.
 *
 * <p>Resilience4j uses its {@code decorateCompletionStage} variants with the same settings: count
 * window of 100 for the breaker, 3 attempts for the retry, 1 s for the time limiter, 1000 concurrent
 * calls for the bulkhead, one billion permits per second for the rate limiter. Its retry and time
 * limiter need a {@code ScheduledExecutorService}, given one daemon thread here; resilience4cata uses
 * its own single timer thread. {@code *Stack} nests retry, circuit breaker, timeout and bulkhead in
 * the same order in both libraries.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class AsyncBenchmark {

    private AsyncService direct;
    private AsyncService resilience4cata;
    private ScheduledExecutorService scheduler;
    private Supplier<CompletionStage<String>> circuitBreaker;
    private Supplier<CompletionStage<String>> retry;
    private Supplier<CompletionStage<String>> timeout;
    private Supplier<CompletionStage<String>> bulkhead;
    private Supplier<CompletionStage<String>> rateLimiter;
    private Supplier<CompletionStage<String>> stack;

    /** Builds both libraries with equivalent configuration. */
    @Setup
    public void setup() {
        direct = new AsyncService.Impl();
        resilience4cata = Resilient.of(AsyncService.class, new AsyncService.Impl(), ResilienceRegistry.builder().build());

        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().factory());
        AsyncService.Impl impl = new AsyncService.Impl();
        CircuitBreakerConfig breakerConfig = CircuitBreakerConfig.custom().slidingWindowSize(100).minimumNumberOfCalls(100).build();
        RetryConfig retryConfig = RetryConfig.custom().maxAttempts(3).build();
        TimeLimiterConfig timeLimiterConfig = TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(1)).build();
        BulkheadConfig bulkheadConfig = BulkheadConfig.custom().maxConcurrentCalls(1000).maxWaitDuration(Duration.ZERO).build();

        circuitBreaker = CircuitBreaker.decorateCompletionStage(CircuitBreaker.of("cb", breakerConfig), impl::circuitBreaker);
        retry = Retry.decorateCompletionStage(Retry.of("retry", retryConfig), scheduler, impl::retry);
        timeout = TimeLimiter.of(timeLimiterConfig).decorateCompletionStage(scheduler, impl::timeout);
        bulkhead = Bulkhead.decorateCompletionStage(Bulkhead.of("bulkhead", bulkheadConfig), impl::bulkhead);
        rateLimiter = RateLimiter.decorateCompletionStage(RateLimiter.of("rl", RateLimiterConfig.custom()
                .limitForPeriod(1_000_000_000).limitRefreshPeriod(Duration.ofSeconds(1)).timeoutDuration(Duration.ZERO)
                .build()), impl::rateLimiter);

        Supplier<CompletionStage<String>> inner = Bulkhead.decorateCompletionStage(Bulkhead.of("stack", bulkheadConfig), impl::stack);
        inner = TimeLimiter.of(timeLimiterConfig).decorateCompletionStage(scheduler, inner);
        inner = CircuitBreaker.decorateCompletionStage(CircuitBreaker.of("stack", breakerConfig), inner);
        stack = Retry.decorateCompletionStage(Retry.of("stack", retryConfig), scheduler, inner);
    }

    /** Stops Resilience4j's scheduler. */
    @TearDown
    public void tearDown() {
        scheduler.shutdownNow();
    }

    @Benchmark
    public CompletionStage<String> baseline() {
        return direct.circuitBreaker();
    }

    @Benchmark
    public CompletionStage<String> resilience4cataCircuitBreaker() {
        return resilience4cata.circuitBreaker();
    }

    @Benchmark
    public CompletionStage<String> resilience4jCircuitBreaker() {
        return circuitBreaker.get();
    }

    @Benchmark
    public CompletionStage<String> resilience4cataRetry() {
        return resilience4cata.retry();
    }

    @Benchmark
    public CompletionStage<String> resilience4jRetry() {
        return retry.get();
    }

    @Benchmark
    public CompletionStage<String> resilience4cataTimeout() {
        return resilience4cata.timeout();
    }

    @Benchmark
    public CompletionStage<String> resilience4jTimeout() {
        return timeout.get();
    }

    @Benchmark
    public CompletionStage<String> resilience4cataBulkhead() {
        return resilience4cata.bulkhead();
    }

    @Benchmark
    public CompletionStage<String> resilience4jBulkhead() {
        return bulkhead.get();
    }

    @Benchmark
    public CompletionStage<String> resilience4cataRateLimiter() {
        return resilience4cata.rateLimiter();
    }

    @Benchmark
    public CompletionStage<String> resilience4jRateLimiter() {
        return rateLimiter.get();
    }

    @Benchmark
    public CompletionStage<String> resilience4cataStack() {
        return resilience4cata.stack();
    }

    @Benchmark
    public CompletionStage<String> resilience4jStack() {
        return stack.get();
    }
}
