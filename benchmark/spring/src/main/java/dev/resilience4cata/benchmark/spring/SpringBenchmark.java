package dev.resilience4cata.benchmark.spring;

import dev.resilience4cata.ResilienceRegistry;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import java.util.HashMap;
import java.util.Map;
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
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The beans that Spring Boot hands to an application, called through their proxies: resilience4cata's generated
 * subclass ({@link CataCalls}) against resilience4j-spring-boot4's aspects ({@link R4jCalls}), and the plain bean
 * ({@link PlainCalls}). Run it with {@code -t 1} and {@code -t 4}: every thread shares the same bean and policy
 * instances.
 *
 * <p>Equivalent settings, as in the core benchmarks: retry 3 attempts with no delay and no jitter; circuit breaker
 * count window of 100 calls evaluated after 100, opening at 50%, stackless rejections; bulkhead of 1000 calls with
 * no wait; rate limiter of 10⁹ permits per second with no wait (never reached); timeout 1 s on an already completed
 * future. {@code stack} is retry, circuit breaker, rate limiter and bulkhead on one method.
 *
 * <p>Both libraries run with their Spring Boot defaults otherwise. With no Micrometer registry and no actuator in
 * the context, neither registers metrics or health indicators. Resilience4j's {@code TimeLimiter} supports only
 * asynchronous methods, so the timeout is compared on a {@code CompletableFuture} method. Resilience4j 2.4.0 calls
 * {@code Thread.sleep(0)} between attempts ({@code RetryBenchmark} in the core benchmarks measures it apart); it is
 * left in, because that is what a Spring application gets.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class SpringBenchmark {

    /** Resilience4j's instances, configured as {@link CataCalls}'s annotations. */
    static final Map<String, Object> RESILIENCE4J = resilience4j();

    private ConfigurableApplicationContext context;
    private PlainCalls plain;
    private CataCalls resilience4cata;
    private R4jCalls resilience4j;

    /** The application: component scan of this package, no web server. */
    @SpringBootApplication(proxyBeanMethods = false)
    public static class Application {
    }

    /** Per-thread failures for the retried calls. */
    @State(Scope.Thread)
    public static class Attempts {
        final Flip flip = new Flip();
    }

    @Setup
    public void setup() {
        SpringApplication application = new SpringApplication(Application.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setDefaultProperties(RESILIENCE4J);
        application.setLogStartupInfo(false);
        context = application.run();
        plain = context.getBean(PlainCalls.class);
        resilience4cata = context.getBean(CataCalls.class);
        resilience4j = context.getBean(R4jCalls.class);
        if (!resilience4cata.getClass().getName().endsWith("CataCalls$Resilient")
                || !resilience4j.getClass().getName().contains("$$SpringCGLIB$$")) {
            throw new IllegalStateException("unexpected beans: " + resilience4cata.getClass() + ", " + resilience4j.getClass());
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

    private static Map<String, Object> resilience4j() {
        Map<String, Object> p = new HashMap<>();
        for (String retry : new String[] {"retry", "stack"}) {
            p.put("resilience4j.retry.instances." + retry + ".maxAttempts", 3);
            p.put("resilience4j.retry.instances." + retry + ".waitDuration", "0ms");
        }
        for (String breaker : new String[] {"breaker", "open", "stack"}) {
            String prefix = "resilience4j.circuitbreaker.instances." + breaker + ".";
            p.put(prefix + "slidingWindowType", "COUNT_BASED");
            p.put(prefix + "slidingWindowSize", 100);
            p.put(prefix + "minimumNumberOfCalls", 100);
            p.put(prefix + "failureRateThreshold", 50);
            p.put(prefix + "waitDurationInOpenState", "60s");
            p.put(prefix + "writableStackTraceEnabled", false);
        }
        for (String bulkhead : new String[] {"bulkhead", "stack"}) {
            p.put("resilience4j.bulkhead.instances." + bulkhead + ".maxConcurrentCalls", 1000);
            p.put("resilience4j.bulkhead.instances." + bulkhead + ".maxWaitDuration", "0ms");
        }
        for (String limiter : new String[] {"limiter", "stack"}) {
            p.put("resilience4j.ratelimiter.instances." + limiter + ".limitForPeriod", 1_000_000_000);
            p.put("resilience4j.ratelimiter.instances." + limiter + ".limitRefreshPeriod", "1s");
            p.put("resilience4j.ratelimiter.instances." + limiter + ".timeoutDuration", "0ms");
        }
        p.put("resilience4j.timelimiter.instances.timeout.timeoutDuration", "1s");
        return Map.copyOf(p);
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
}
