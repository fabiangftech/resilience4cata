package dev.resilience4cata.benchmark;

import dev.resilience4cata.generated.Policies;
import dev.resilience4cata.BulkheadFullException;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
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

/**
 * Bulkhead overhead. Run with {@code -t 1} and {@code -t 4}: every thread shares the same bulkhead.
 *
 * <ul>
 *   <li>{@code *Free}: a limit of 1000, never reached; no waiting.</li>
 *   <li>{@code *Waiting}: the same, but configured to wait up to 100 ms for a slot, so resilience4cata
 *       uses its {@code Semaphore} mode.</li>
 *   <li>{@code *Full}: a limit of 1 held by the setup, so every call is rejected.</li>
 * </ul>
 *
 * <p>Resilience4j uses its {@code SemaphoreBulkhead} with the same limits, and stack traces disabled
 * on its rejection exception ({@code writableStackTraceEnabled(false)}), which is fair to it:
 * resilience4cata's rejection exception has none either.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class BulkheadBenchmark {

    private LimitedService direct;
    private LimitedService resilience4cata;
    private Supplier<String> free;
    private Supplier<String> waiting;
    private Supplier<String> full;

    /** Builds both libraries with equivalent configuration and fills the {@code *Full} bulkheads. */
    @Setup
    public void setup() {
        direct = new LimitedService.Impl();
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .bulkhead("bench", b -> b.maxConcurrent(1000))
                .bulkhead("bench-wait", b -> b.maxConcurrent(1000).maxWait(Duration.ofMillis(100)))
                .bulkhead("bench-full", b -> b.maxConcurrent(1))
                .build();
        resilience4cata = Resilient.of(LimitedService.class, new LimitedService.Impl(), registry);
        if (!Policies.bulkhead(registry, "setup", "full", "bench-full", null, false).tryEnter()) {
            throw new IllegalStateException("could not fill the bulkhead");
        }

        LimitedService.Impl impl = new LimitedService.Impl();
        free = Bulkhead.decorateSupplier(bulkhead("free", 1000, Duration.ZERO), impl::call);
        waiting = Bulkhead.decorateSupplier(bulkhead("waiting", 1000, Duration.ofMillis(100)), impl::callWaiting);
        Bulkhead fullBulkhead = bulkhead("full", 1, Duration.ZERO);
        if (!fullBulkhead.tryAcquirePermission()) {
            throw new IllegalStateException("could not fill the bulkhead");
        }
        full = Bulkhead.decorateSupplier(fullBulkhead, impl::callFull);
    }

    private static Bulkhead bulkhead(String name, int maxConcurrent, Duration maxWait) {
        return Bulkhead.of(name, BulkheadConfig.custom()
                .maxConcurrentCalls(maxConcurrent)
                .maxWaitDuration(maxWait)
                .writableStackTraceEnabled(false)
                .build());
    }

    @Benchmark
    public String baseline() {
        return direct.call();
    }

    @Benchmark
    public String resilience4cataFree() {
        return resilience4cata.call();
    }

    @Benchmark
    public String resilience4jFree() {
        return free.get();
    }

    @Benchmark
    public String resilience4cataWaiting() {
        return resilience4cata.callWaiting();
    }

    @Benchmark
    public String resilience4jWaiting() {
        return waiting.get();
    }

    @Benchmark
    public String resilience4cataFull() {
        try {
            return resilience4cata.callFull();
        } catch (BulkheadFullException e) {
            return "rejected";
        }
    }

    @Benchmark
    public String resilience4jFull() {
        try {
            return full.get();
        } catch (io.github.resilience4j.bulkhead.BulkheadFullException e) {
            return "rejected";
        }
    }
}
