package dev.resilience4cata.benchmark.quarkus;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Resilience4j in a Quarkus bean, used in code: it has no Quarkus extension, so this is what an application writes.
 * The decorated calls are built once, with the settings of {@link CataCalls}'s annotations; the stack nests retry,
 * circuit breaker, rate limiter and bulkhead, resilience4cata's default order. Its time limiter needs a scheduler:
 * one daemon thread that drops cancelled tasks, as in the core {@code AsyncBenchmark}.
 */
@Singleton
public class R4jCalls {

    private final ScheduledExecutorService scheduler = scheduler();
    private final Supplier<String> retry;
    private final Function<Flip, String> retryOnce;
    private final Supplier<String> circuitBreaker;
    private final CircuitBreaker openBreaker;
    private final Supplier<String> open;
    private final Supplier<String> bulkhead;
    private final Supplier<String> rateLimiter;
    private final Supplier<CompletionStage<String>> timeoutAsync;
    private final Supplier<String> stack;

    public R4jCalls() {
        retry = Retry.decorateSupplier(retry("retry"), R4jCalls::ok);
        retryOnce = Retry.decorateFunction(retry("retry-once"), flip -> {
            flip.attempt();
            return "ok";
        });
        circuitBreaker = CircuitBreaker.decorateSupplier(breaker("breaker"), R4jCalls::ok);
        openBreaker = breaker("open");
        openBreaker.transitionToForcedOpenState();
        open = CircuitBreaker.decorateSupplier(openBreaker, R4jCalls::ok);
        bulkhead = Bulkhead.decorateSupplier(bulkhead("bulkhead"), R4jCalls::ok);
        rateLimiter = RateLimiter.decorateSupplier(limiter("limiter"), R4jCalls::ok);
        TimeLimiter timeLimiter = TimeLimiter.of(TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(1)).build());
        timeoutAsync = timeLimiter.decorateCompletionStage(scheduler, () -> PlainCalls.DONE);
        stack = Retry.decorateSupplier(retry("stack"), CircuitBreaker.decorateSupplier(breaker("stack"),
                RateLimiter.decorateSupplier(limiter("stack"), Bulkhead.decorateSupplier(bulkhead("stack"), R4jCalls::ok))));
    }

    /** One daemon thread that drops cancelled timeouts at once, as in the core {@code AsyncBenchmark}. */
    private static ScheduledExecutorService scheduler() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().daemon().factory());
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    private static String ok() {
        return "ok";
    }

    private static Retry retry(String name) {
        return Retry.of(name, RetryConfig.custom().maxAttempts(3).waitDuration(Duration.ZERO)
                .retryExceptions(Exception.class).build());
    }

    private static CircuitBreaker breaker(String name) {
        return CircuitBreaker.of(name, CircuitBreakerConfig.custom()
                .slidingWindow(100, 100, CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(60))
                .writableStackTraceEnabled(false)
                .build());
    }

    private static Bulkhead bulkhead(String name) {
        return Bulkhead.of(name, BulkheadConfig.custom().maxConcurrentCalls(1000).maxWaitDuration(Duration.ZERO).build());
    }

    private static RateLimiter limiter(String name) {
        return RateLimiter.of(name, RateLimiterConfig.custom().limitForPeriod(1_000_000_000)
                .limitRefreshPeriod(Duration.ofSeconds(1)).timeoutDuration(Duration.ZERO).build());
    }

    @PreDestroy
    void close() {
        scheduler.shutdownNow();
    }

    public String retry() {
        return retry.get();
    }

    public String retryOnce(Flip flip) {
        return retryOnce.apply(flip);
    }

    public String circuitBreaker() {
        return circuitBreaker.get();
    }

    /** Rejected: the breaker is forced open. */
    public String open() {
        return open.get();
    }

    boolean isOpen() {
        try {
            open.get();
            return false;
        } catch (CallNotPermittedException e) {
            return openBreaker.getState() == CircuitBreaker.State.FORCED_OPEN;
        }
    }

    public String bulkhead() {
        return bulkhead.get();
    }

    public String rateLimiter() {
        return rateLimiter.get();
    }

    public CompletableFuture<String> timeoutAsync() {
        return timeoutAsync.get().toCompletableFuture();
    }

    public String stack() {
        return stack.get();
    }
}
