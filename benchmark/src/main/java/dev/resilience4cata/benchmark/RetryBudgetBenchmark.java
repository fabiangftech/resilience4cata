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
 * Cost of a retry budget. Run it with several thread counts ({@code -t 1}, {@code -t 4}): the
 * registries are shared, so every thread uses the same retry instances and, with a budget, the same
 * budget. Each thread has its own wrapper and fake service, so the services do not contend.
 *
 * <p>Same retry settings as {@link RetryBenchmark} (3 attempts, no delay, no jitter, no listeners).
 * {@code *Budget*} adds {@code budgetPercent = 1000} and {@code budgetMinRetriesPerSecond = 10}, so that
 * the one retry of each {@code RetryOnce} call is always granted: the benchmarks measure the
 * bookkeeping, not refusals. (At exactly 100 % every call's retry is at the limit, and the budget's
 * approximate counting refuses some under contention, by design.)
 *
 * <ul>
 *   <li>{@code resilience4cataSuccess}, {@code resilience4cataRetryOnce}: no budget, the reference.</li>
 *   <li>{@code resilience4cataBudgetSuccess}: every call succeeds; each call counts itself in its
 *       thread's stripe of the budget.</li>
 *   <li>{@code resilience4cataBudgetRetryOnce}: each call fails once and is retried: one clock read,
 *       a sum of the stripes, and a compare-and-set on the shared retry count per call.</li>
 * </ul>
 *
 * <p>Resilience4j has no retry budget, so there is no Resilience4j counterpart.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class RetryBudgetBenchmark {

    private ResilienceRegistry plain;
    private ResilienceRegistry budgeted;

    /** Builds the two shared registries. */
    @Setup
    public void setup() {
        plain = ResilienceRegistry.builder()
                .retry("bench", r -> settings(r))
                .build();
        budgeted = ResilienceRegistry.builder()
                .retry("bench", r -> settings(r).budgetPercent(1000).budgetMinRetriesPerSecond(10))
                .build();
    }

    private static RetryConfig.Builder settings(RetryConfig.Builder r) {
        return r.maxAttempts(3).delay(Duration.ZERO).backoff(RetryConfig.Backoff.FIXED).jitter(RetryConfig.Jitter.NONE);
    }

    /** One thread's wrappers, over the shared registries' retry instances. */
    @State(Scope.Thread)
    public static class Calls {
        Service plain;
        Service budgeted;

        /** Wraps a fake service of this thread. */
        @Setup
        public void setup(RetryBudgetBenchmark shared) {
            plain = Resilient.of(Service.class, new FakeService(), shared.plain);
            budgeted = Resilient.of(Service.class, new FakeService(), shared.budgeted);
        }
    }

    @Benchmark
    public String resilience4cataSuccess(Calls calls) {
        return calls.plain.call();
    }

    @Benchmark
    public String resilience4cataBudgetSuccess(Calls calls) {
        return calls.budgeted.call();
    }

    @Benchmark
    public String resilience4cataRetryOnce(Calls calls) {
        return calls.plain.failOnce();
    }

    @Benchmark
    public String resilience4cataBudgetRetryOnce(Calls calls) {
        return calls.budgeted.failOnce();
    }
}
