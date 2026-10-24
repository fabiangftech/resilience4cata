package dev.resilience4cata.benchmark;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 * Timeout overhead on calls that finish in time (1 s timeout, trivial work). Run with {@code -t 1}
 * and {@code -t 4}.
 *
 * <p>resilience4cata runs the call on the caller's thread; a shared watchdog interrupts it if it runs
 * late. Resilience4j's {@code TimeLimiter} needs a {@code Future}, so a synchronous call has to be
 * handed to another thread: that is how it is used for blocking code, and what is measured here, with
 * a fixed pool of 4 platform threads.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class TimeoutBenchmark {

    private TimedService direct;
    private TimedService resilience4cata;
    private TimeLimiter timeLimiter;
    private TimedService.Impl forResilience4j;
    private ExecutorService executor;

    /** Builds both libraries with equivalent configuration. */
    @Setup
    public void setup() {
        direct = new TimedService.Impl();
        resilience4cata = Resilient.of(TimedService.class, new TimedService.Impl(), ResilienceRegistry.builder().build());
        timeLimiter = TimeLimiter.of(TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(1)).build());
        forResilience4j = new TimedService.Impl();
        executor = Executors.newFixedThreadPool(4);
    }

    /** Stops the Resilience4j executor. */
    @TearDown
    public void tearDown() {
        executor.shutdownNow();
    }

    @Benchmark
    public String baseline() {
        return direct.call();
    }

    @Benchmark
    public String resilience4cata() {
        return resilience4cata.call();
    }

    @Benchmark
    public String resilience4j() throws Exception {
        return timeLimiter.executeFutureSupplier(() -> CompletableFuture.supplyAsync(forResilience4j::call, executor));
    }
}
