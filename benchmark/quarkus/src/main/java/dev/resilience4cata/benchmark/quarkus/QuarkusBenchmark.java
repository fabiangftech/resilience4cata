package dev.resilience4cata.benchmark.quarkus;

import dev.resilience4cata.ResilienceRegistry;
import io.quarkus.arc.Arc;
import io.quarkus.runtime.Application;
import io.smallrye.faulttolerance.api.CircuitBreakerMaintenance;
import io.smallrye.faulttolerance.api.CircuitBreakerState;
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
 * The beans that Quarkus hands to an application: resilience4cata's generated subclass ({@link CataCalls}) against
 * SmallRye Fault Tolerance's interceptors ({@link FaultToleranceCalls}), Resilience4j used in code
 * ({@link R4jCalls}: it has no Quarkus extension), and the plain bean ({@link PlainCalls}). All are
 * {@code @Singleton}, so no client proxy is in the way. JMH runs the uber-jar that {@code quarkusBuild} writes and
 * each fork starts the augmented application. Run it with {@code -t 1} and {@code -t 4}: every thread shares the
 * same bean and policy instances.
 *
 * <p>The settings are those of the Spring and Micronaut benchmarks: retry 3 attempts with no delay and no jitter;
 * circuit breaker count window of 100 calls evaluated after 100, opening at 50%, stackless rejections; bulkhead of
 * 1000 calls with no wait; rate limiter of 10⁹ permits per second with no wait (never reached); timeout 1 s.
 * {@code stack} is retry, circuit breaker, rate limiter and bulkhead on one method. Only resilience4cata and SmallRye
 * Fault Tolerance time synchronous calls ({@code timeout}); only resilience4cata and Resilience4j time a
 * {@code CompletableFuture} without moving the call to another thread ({@code timeoutAsync}, on an already completed
 * future). SmallRye's breaker cannot be forced open: the setup fails 100 calls to open it, and it stays open for 60
 * s, longer than a fork's iterations. SmallRye's rejections are its own exceptions, with a stack trace.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class QuarkusBenchmark {

    private Application application;
    private PlainCalls plain;
    private CataCalls resilience4cata;
    private R4jCalls resilience4j;
    private FaultToleranceCalls faultTolerance;

    /** Per-thread failures for the retried calls. */
    @State(Scope.Thread)
    public static class Attempts {
        final Flip flip = new Flip();
    }

    @Setup
    public void setup() throws ReflectiveOperationException {
        // The class Quarkus generates at build time to start the application, as its main method does.
        application = (Application) Class.forName("io.quarkus.runner.ApplicationImpl").getDeclaredConstructor()
                .newInstance();
        application.start(new String[0]);
        plain = bean(PlainCalls.class);
        resilience4cata = bean(CataCalls.class);
        resilience4j = bean(R4jCalls.class);
        faultTolerance = bean(FaultToleranceCalls.class);
        if (!resilience4cata.getClass().getName().endsWith("CataCalls$Resilient")
                || !faultTolerance.getClass().getName().endsWith("_Subclass")) {
            throw new IllegalStateException("unexpected beans: " + resilience4cata.getClass() + ", "
                    + faultTolerance.getClass());
        }
        bean(ResilienceRegistry.class).circuitBreaker("open").forceOpen();
        openFaultToleranceBreaker();
        if (!resilience4j.isOpen()) {
            throw new IllegalStateException("Resilience4j's breaker is not open");
        }
    }

    private void openFaultToleranceBreaker() {
        faultTolerance.failing = true;
        for (int i = 0; i < 100; i++) {
            try {
                faultTolerance.open();
            } catch (RuntimeException expected) {
                // counted as a failure
            }
        }
        faultTolerance.failing = false;
        CircuitBreakerState state = bean(CircuitBreakerMaintenance.class).currentState("open");
        if (state != CircuitBreakerState.OPEN) {
            throw new IllegalStateException("SmallRye's breaker is " + state + " after 100 failures");
        }
    }

    private static <T> T bean(Class<T> type) {
        T bean = Arc.container().instance(type).get();
        if (bean == null) {
            throw new IllegalStateException("no bean " + type.getName());
        }
        return bean;
    }

    @TearDown
    public void tearDown() {
        application.stop();
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
    public String faultToleranceRetry() {
        return faultTolerance.retry();
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
    public String faultToleranceRetryOnce(Attempts attempts) {
        return faultTolerance.retryOnce(attempts.flip);
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
    public String faultToleranceCircuitBreaker() {
        return faultTolerance.circuitBreaker();
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
    public String faultToleranceOpen() {
        try {
            return faultTolerance.open();
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
    public String faultToleranceBulkhead() {
        return faultTolerance.bulkhead();
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
    public String faultToleranceRateLimiter() {
        return faultTolerance.rateLimiter();
    }

    @Benchmark
    public String baselineTimeout() {
        return plain.timeout();
    }

    @Benchmark
    public String resilience4cataTimeout() {
        return resilience4cata.timeout();
    }

    @Benchmark
    public String faultToleranceTimeout() {
        return faultTolerance.timeout();
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
    public String faultToleranceStack() {
        return faultTolerance.stack();
    }
}
