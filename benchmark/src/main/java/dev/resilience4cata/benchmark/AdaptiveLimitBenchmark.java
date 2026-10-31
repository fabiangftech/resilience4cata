package dev.resilience4cata.benchmark;

import dev.resilience4cata.generated.Policies;
import com.netflix.concurrency.limits.Limiter;
import com.netflix.concurrency.limits.limit.Gradient2Limit;
import com.netflix.concurrency.limits.limit.WindowedLimit;
import com.netflix.concurrency.limits.limiter.SimpleLimiter;
import dev.resilience4cata.LimitExceededException;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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
import org.openjdk.jmh.annotations.Warmup;

/**
 * Adaptive limit overhead. Run with {@code -t 1} and {@code -t 4}: every thread shares the same limit.
 *
 * <ul>
 *   <li>{@code resilience4cata}: {@code @AdaptiveLimit} with Gradient2, a limit of 1000 that the calls never
 *       reach (the limit stays put: the calls use less than half of it). Each call reads the clock twice and
 *       records its latency; a window ends every second.</li>
 *   <li>{@code resilience4cataFull}: a limit of 1, held by the setup, so every call is rejected.</li>
 *   <li>{@code resilience4cataAsync}: the async form, on an already completed stage.</li>
 *   <li>{@code bulkhead}: a fixed {@code @Bulkhead(maxConcurrent = 1000)}: what the adaptivity costs.</li>
 *   <li>{@code netflix}: Netflix concurrency-limits 0.5.4, {@code SimpleLimiter} with a {@code Gradient2Limit}
 *       wrapped in a {@code WindowedLimit}, the equivalent of {@code @AdaptiveLimit}'s windows; and
 *       {@code netflixFull}, the same limiter with its only permit held by the setup.</li>
 * </ul>
 *
 * <p>Fairness adjustments to Netflix's defaults, so that both do the same work: the same limits (initial and
 * maximum 1000, minimum 1; Netflix's minimum default is 20) and tolerance (1.5, both defaults); windows of 1 s and at least 10
 * samples (Netflix's defaults); and {@code minRttThreshold(0)}: by default Netflix's {@code WindowedLimit}
 * drops samples faster than 100 µs before recording them, which would skip the per-call work for this
 * benchmark's trivial calls, while {@code @AdaptiveLimit} records every sample. Netflix's limiter is called
 * as its documentation shows: {@code acquire} returns an {@code Optional<Listener>}, and the call reports
 * {@code onSuccess}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class AdaptiveLimitBenchmark {

    private AdaptiveService direct;
    private AdaptiveService resilience4cata;
    private Limiter<Void> netflix;
    private Limiter<Void> netflixFull;

    /** Builds both libraries with equivalent configuration and fills the {@code *Full} limits. */
    @Setup
    public void setup() {
        direct = new AdaptiveService.Impl();
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .adaptiveLimit("bench", a -> a.initialLimit(1000).minLimit(1).maxLimit(1000))
                .adaptiveLimit("bench-full", a -> a.initialLimit(1).minLimit(1).maxLimit(1))
                .build();
        resilience4cata = Resilient.of(AdaptiveService.class, new AdaptiveService.Impl(), registry);
        Policies.adaptiveLimit(registry, "setup", "full", "bench-full", null, false).acquire(); // holds the only slot

        netflix = limiter(1000);
        netflixFull = limiter(1);
        if (netflixFull.acquire(null).isEmpty()) {
            throw new IllegalStateException("could not fill the Netflix limiter");
        }
    }

    private static Limiter<Void> limiter(int limit) {
        Gradient2Limit gradient = Gradient2Limit.newBuilder()
                .initialLimit(limit)
                .minLimit(1)
                .maxConcurrency(limit)
                .rttTolerance(1.5)
                .build();
        WindowedLimit windowed = WindowedLimit.newBuilder()
                .minWindowTime(Duration.ofSeconds(1).toMillis(), TimeUnit.MILLISECONDS)
                .maxWindowTime(Duration.ofSeconds(1).toMillis(), TimeUnit.MILLISECONDS)
                .windowSize(10)
                .minRttThreshold(0, TimeUnit.NANOSECONDS)
                .build(gradient);
        return SimpleLimiter.newBuilder().limit(windowed).build();
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
    public String resilience4cataFull() {
        try {
            return resilience4cata.callFull();
        } catch (LimitExceededException e) {
            return "rejected";
        }
    }

    @Benchmark
    public CompletableFuture<String> resilience4cataAsync() {
        return resilience4cata.callAsync();
    }

    @Benchmark
    public String bulkhead() {
        return resilience4cata.callBulkhead();
    }

    @Benchmark
    public String netflix() {
        Optional<Limiter.Listener> listener = netflix.acquire(null);
        if (listener.isEmpty()) {
            return "rejected";
        }
        String result = direct.call();
        listener.get().onSuccess();
        return result;
    }

    @Benchmark
    public String netflixFull() {
        Optional<Limiter.Listener> listener = netflixFull.acquire(null);
        if (listener.isEmpty()) {
            return "rejected";
        }
        String result = direct.call();
        listener.get().onSuccess();
        return result;
    }
}
