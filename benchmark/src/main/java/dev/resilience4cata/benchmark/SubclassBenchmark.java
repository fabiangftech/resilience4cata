package dev.resilience4cata.benchmark;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.RetryConfig;
import java.time.Duration;
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
 * The two generated styles with the same retry, single thread: the interface wrapper
 * ({@code Resilient.of}, as in {@link RetryBenchmark}) and the subclass ({@code Resilient.create}), which
 * runs the same layers but calls {@code super.call()} instead of {@code delegate.call()}. Same settings as
 * {@link RetryBenchmark}: 3 attempts, no delay, no jitter.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class SubclassBenchmark {

    private Service wrapper;
    private SubclassService subclass;

    /** Both styles share one registry, so they resolve the same configuration. */
    @Setup
    public void setup() {
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .retry("bench", r -> r.maxAttempts(3).delay(Duration.ZERO)
                        .backoff(RetryConfig.Backoff.FIXED).jitter(RetryConfig.Jitter.NONE))
                .build();
        wrapper = Resilient.of(Service.class, new FakeService(), registry);
        subclass = Resilient.create(SubclassService.class, registry);
    }

    @Benchmark
    public String wrapperSuccess() {
        return wrapper.call();
    }

    @Benchmark
    public String subclassSuccess() {
        return subclass.call();
    }

    @Benchmark
    public String wrapperRetryOnce() {
        return wrapper.failOnce();
    }

    @Benchmark
    public String subclassRetryOnce() {
        return subclass.failOnce();
    }
}
