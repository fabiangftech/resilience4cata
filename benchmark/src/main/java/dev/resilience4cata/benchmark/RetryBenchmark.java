package dev.resilience4cata.benchmark;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.RetryConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.internal.RetryImpl;
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
import org.openjdk.jmh.infra.BenchmarkParams;

/**
 * Retry overhead, single thread. Both libraries use the same settings: 3 attempts, no delay
 * between attempts, no jitter, every exception retried, no event listeners.
 *
 * <ul>
 *   <li>{@code *Success}: the first attempt succeeds, the common case.</li>
 *   <li>{@code *RetryOnce}: the first attempt fails and the second succeeds.</li>
 *   <li>{@code baseline*}: the same calls without any library.</li>
 * </ul>
 *
 * <p>Resilience4j 2.4.0 calls {@code Thread.sleep} between attempts even when the delay is zero.
 * {@code resilience4jRetryOnceNoSleep} replaces that sleep with a no-op, through Resilience4j's own
 * {@code RetryImpl.setSleepFunction}, to show the cost of its retry machinery alone. JMH runs every
 * benchmark in its own JVM, so this static setting does not leak into the other benchmarks.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class RetryBenchmark {

    private Service direct;
    private Service resilience4cata;
    private Supplier<String> resilience4jSuccess;
    private Supplier<String> resilience4jRetryOnce;

    /** Builds both libraries with equivalent configuration. */
    @Setup
    public void setup(BenchmarkParams params) {
        if (params.getBenchmark().endsWith("NoSleep")) {
            RetryImpl.setSleepFunction(millis -> { });
        }
        direct = new FakeService();

        ResilienceRegistry registry = ResilienceRegistry.builder()
                .retry("bench", r -> r.maxAttempts(3).delay(Duration.ZERO)
                        .backoff(RetryConfig.Backoff.FIXED).jitter(RetryConfig.Jitter.NONE))
                .build();
        resilience4cata = Resilient.of(Service.class, new FakeService(), registry);

        Retry retry = Retry.of("bench", io.github.resilience4j.retry.RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ZERO)
                .retryExceptions(Exception.class)
                .build());
        FakeService forResilience4j = new FakeService();
        resilience4jSuccess = Retry.decorateSupplier(retry, forResilience4j::call);
        resilience4jRetryOnce = Retry.decorateSupplier(retry, forResilience4j::failOnce);
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
    public String resilience4jSuccess() {
        return resilience4jSuccess.get();
    }

    @Benchmark
    public String baselineRetryOnce() {
        try {
            return direct.failOnce();
        } catch (RuntimeException e) {
            return direct.failOnce();
        }
    }

    @Benchmark
    public String resilience4cataRetryOnce() {
        return resilience4cata.failOnce();
    }

    @Benchmark
    public String resilience4jRetryOnce() {
        return resilience4jRetryOnce.get();
    }

    @Benchmark
    public String resilience4jRetryOnceNoSleep() {
        return resilience4jRetryOnce.get();
    }
}
