package dev.resilience4cata.benchmark.micronaut;

import dev.resilience4cata.ResilienceRegistry;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micronaut.context.ApplicationContext;
import java.util.concurrent.TimeUnit;
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
 * The beans that Micronaut 5 hands to an application: resilience4cata's generated subclass ({@link CataCalls})
 * against resilience4j-micronaut 2.4.0's interceptors ({@link R4jCalls}) and Micronaut's own retry and breaker
 * ({@link MicronautCalls}), and the plain bean ({@link PlainCalls}). Run it with {@code -t 1} and {@code -t 4}:
 * every thread shares the same bean and policy instances.
 *
 * <p>The settings are those of the Spring benchmark: retry 3 attempts with no delay and no jitter; circuit breaker
 * count window of 100 calls evaluated after 100, opening at 50%, stackless rejections; bulkhead of 1000 calls with
 * no wait; rate limiter of 10⁹ permits per second with no wait (never reached); timeout 1 s on an already completed
 * future. {@code stack} is retry, circuit breaker, rate limiter and bulkhead on one method. Resilience4j's are in
 * {@code application.properties}. resilience4j-micronaut 2.4.0 is built for Micronaut 4; it runs on Micronaut 5
 * here, which its project does not claim. Resilience4j's {@code TimeLimiter} supports only asynchronous methods,
 * so the timeout is compared on a {@code CompletableFuture} method.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class MicronautBenchmark {

    private ApplicationContext context;
    private PlainCalls plain;
    private CataCalls resilience4cata;
    private R4jCalls resilience4j;
    private MicronautCalls micronaut;

    /** Per-thread failures for the retried calls. */
    @State(Scope.Thread)
    public static class Attempts {
        final Flip flip = new Flip();
    }

    @Setup
    public void setup() {
        context = ApplicationContext.run();
        plain = context.getBean(PlainCalls.class);
        resilience4cata = context.getBean(CataCalls.class);
        resilience4j = context.getBean(R4jCalls.class);
        micronaut = context.getBean(MicronautCalls.class);
        if (!resilience4cata.getClass().getName().endsWith("CataCalls$Resilient")
                || !resilience4j.getClass().getName().endsWith("$Intercepted")
                || !micronaut.getClass().getName().endsWith("$Intercepted")) {
            throw new IllegalStateException("unexpected beans: " + resilience4cata.getClass() + ", "
                    + resilience4j.getClass() + ", " + micronaut.getClass());
        }
        context.getBean(ResilienceRegistry.class).circuitBreaker("open").forceOpen();
        context.getBean(CircuitBreakerRegistry.class).circuitBreaker("open").transitionToForcedOpenState();
        verifyResilience4jIsApplied();
    }

    /**
     * Each Resilience4j instance must take part in its call: a bulkhead that is full, a rate limiter with no permit
     * left and a circuit breaker that counted the call. Otherwise a missing interceptor would look like a fast one.
     */
    private void verifyResilience4jIsApplied() {
        resilience4j.circuitBreaker();
        long counted = context.getBean(CircuitBreakerRegistry.class).circuitBreaker("breaker").getMetrics()
                .getNumberOfSuccessfulCalls();
        Bulkhead bulkhead = context.getBean(BulkheadRegistry.class).bulkhead("bulkhead");
        bulkhead.changeConfig(BulkheadConfig.from(bulkhead.getBulkheadConfig()).maxConcurrentCalls(0).build());
        boolean bulkheadApplied = rejects(resilience4j::bulkhead);
        bulkhead.changeConfig(BulkheadConfig.from(bulkhead.getBulkheadConfig()).maxConcurrentCalls(1000).build());
        RateLimiter limiter = context.getBean(RateLimiterRegistry.class).rateLimiter("limiter");
        limiter.changeLimitForPeriod(1);
        limiter.drainPermissions();
        boolean limiterApplied = rejects(resilience4j::rateLimiter);
        limiter.changeLimitForPeriod(1_000_000_000);
        // the new limit applies from the next refresh period, one second
        sleep(1_100);
        if (counted != 1 || !bulkheadApplied || !limiterApplied || !rejects(resilience4j::open)) {
            throw new IllegalStateException("a Resilience4j interceptor is not applied: breaker counted " + counted
                    + ", bulkhead " + bulkheadApplied + ", rate limiter " + limiterApplied);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static boolean rejects(java.util.function.Supplier<String> call) {
        try {
            call.get();
            return false;
        } catch (RuntimeException rejected) {
            return true;
        }
    }

    @TearDown
    public void tearDown() {
        context.close();
    }

    @Benchmark
    public String baselineRetry() {
        return plain.retry();
    }

    @Benchmark
    public String resilience4cataRetry() {
        return resilience4cata.retry();
    }

    @Benchmark
    public String resilience4jRetry() {
        return resilience4j.retry();
    }

    @Benchmark
    public String baselineRetryOnce(Attempts attempts) {
        return plain.retryOnce(attempts.flip);
    }

    @Benchmark
    public String resilience4cataRetryOnce(Attempts attempts) {
        return resilience4cata.retryOnce(attempts.flip);
    }

    @Benchmark
    public String resilience4jRetryOnce(Attempts attempts) {
        return resilience4j.retryOnce(attempts.flip);
    }

    @Benchmark
    public String baselineCircuitBreaker() {
        return plain.circuitBreaker();
    }

    @Benchmark
    public String resilience4cataCircuitBreaker() {
        return resilience4cata.circuitBreaker();
    }

    @Benchmark
    public String resilience4jCircuitBreaker() {
        return resilience4j.circuitBreaker();
    }

    @Benchmark
    public String resilience4cataOpen() {
        try {
            return resilience4cata.open();
        } catch (RuntimeException rejected) {
            return "rejected";
        }
    }

    @Benchmark
    public String resilience4jOpen() {
        try {
            return resilience4j.open();
        } catch (RuntimeException rejected) {
            return "rejected";
        }
    }

    @Benchmark
    public String baselineBulkhead() {
        return plain.bulkhead();
    }

    @Benchmark
    public String resilience4cataBulkhead() {
        return resilience4cata.bulkhead();
    }

    @Benchmark
    public String resilience4jBulkhead() {
        return resilience4j.bulkhead();
    }

    @Benchmark
    public String baselineRateLimiter() {
        return plain.rateLimiter();
    }

    @Benchmark
    public String resilience4cataRateLimiter() {
        return resilience4cata.rateLimiter();
    }

    @Benchmark
    public String resilience4jRateLimiter() {
        return resilience4j.rateLimiter();
    }

    @Benchmark
    public String baselineTimeoutAsync() {
        return plain.timeoutAsync().join();
    }

    @Benchmark
    public String resilience4cataTimeoutAsync() {
        return resilience4cata.timeoutAsync().join();
    }

    @Benchmark
    public String resilience4jTimeoutAsync() {
        return resilience4j.timeoutAsync().join();
    }

    @Benchmark
    public String baselineStack() {
        return plain.stack();
    }

    @Benchmark
    public String resilience4cataStack() {
        return resilience4cata.stack();
    }

    @Benchmark
    public String resilience4jStack() {
        return resilience4j.stack();
    }

    @Benchmark
    public String micronautRetry() {
        return micronaut.retry();
    }

    @Benchmark
    public String micronautRetryOnce(Attempts attempts) {
        return micronaut.retryOnce(attempts.flip);
    }

    @Benchmark
    public String micronautCircuitBreaker() {
        return micronaut.circuitBreaker();
    }
}
