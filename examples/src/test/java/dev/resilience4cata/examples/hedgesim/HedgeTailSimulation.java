package dev.resilience4cata.examples.hedgesim;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.Pipeline;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.examples.hedgesim.Downstream.CancelMode;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Tail latency of hedged calls when the hedges compete with every other call for the same dependency: the question
 * of whether hedging still cuts the tail once the extra attempts load the service that makes the tail.
 *
 * <p>The policies are the library's own ({@link Pipeline}, which runs the same {@code HedgePolicy} and
 * {@code AdaptiveLimitPolicy} as generated code), on a {@link ManualClock}. The dependency is {@link Downstream}: 16
 * workers, one FIFO queue shared by every attempt, service times from {@link ServiceTime#standard}. Calls arrive as a
 * Poisson process (open loop: a slow service does not slow the arrivals down), at a rate that would keep the workers
 * busy the given fraction of the time without hedges. These are results of a model, deterministic for a seed, not
 * wall-clock measurements: they show how the policies react to load, not what a real network adds.
 *
 * <p>Not in {@code check}: {@code ./gradlew :examples:hedgeSimulation} ({@code -Phedge.calls}, default 200,000 per
 * point; {@code -Phedge.seed}). Report in {@code examples/build/hedge-sim/report.md}.
 */
class HedgeTailSimulation {

    static final int WORKERS = 16;
    static final Duration DELAY = Duration.ofMillis(20);
    static final double[] LOADS = {0.30, 0.50, 0.70, 0.85, 0.95};
    /** Calls before this fraction are not measured: the budget window fills and the adaptive limit settles. */
    static final double WARM_UP = 0.10;

    /** What protects the call. */
    enum Setup {
        // tag::hedge-setups[]
        NO_HEDGE("no hedge", null),
        HEDGE_NO_BUDGET("hedge, no budget", b -> b.hedge(h -> h.delay(DELAY).budgetPercent(0))),
        HEDGE_DEFAULT_BUDGET("hedge, default budget", b -> b.hedge(h -> h.delay(DELAY))),
        HEDGE_BUDGET_5("hedge, budget 5 %", b -> b.hedge(h -> h.delay(DELAY).budgetPercent(5))),
        HEDGE_BUDGET_ADAPTIVE("hedge, default budget, adaptive limit", b -> b.hedge(h -> h.delay(DELAY)).adaptiveLimit()),
        THREE_ATTEMPTS_NO_BUDGET("3 attempts, no budget", b -> b.hedge(h -> h.delay(DELAY).maxAttempts(3).budgetPercent(0)));
        // end::hedge-setups[]

        final String label;
        final UnaryOperator<Pipeline.Builder<Long>> layers;

        Setup(String label, UnaryOperator<Pipeline.Builder<Long>> layers) {
            this.label = label;
            this.layers = layers;
        }
    }

    /** One point of the sweep. Latencies in milliseconds, shares in percent. */
    record Result(Setup setup, double load, CancelMode cancelMode, long calls, double p50, double p90, double p99,
            double p999, double max, double requestsPerCall, double utilization, double wastedShare, double failedShare) {
    }

    static Result run(Setup setup, double load, CancelMode cancelMode, int calls, long seed) {
        ManualClock clock = new ManualClock();
        ResilienceRegistry registry = ResilienceTesting.registry(clock, seed).build();
        Downstream downstream = new Downstream(clock, ServiceTime.standard(seed), WORKERS, cancelMode);
        Pipeline<Long> pipeline = setup.layers == null ? null
                : setup.layers.apply(Pipeline.builder(registry, "downstream", Long.class)).build();
        double meanService = ServiceTime.standard(seed).meanNanos(seed ^ 0x5DEECE66DL, 1_000_000);
        double meanGap = meanService / (WORKERS * load);
        SplittableRandom arrivals = new SplittableRandom(seed * 31 + 7);
        int warmUp = (int) (calls * WARM_UP);
        long[] latencies = new long[calls - warmUp];
        int[] measured = {0};
        int[] failed = {0};
        for (int i = 0; i < calls; i++) {
            clock.advance(Math.round(-meanGap * Math.log(1.0 - arrivals.nextDouble())));
            long start = clock.read();
            boolean counted = i >= warmUp;
            CompletableFuture<Long> result = pipeline == null ? downstream.call() : pipeline.executeAsync(downstream::call);
            result.whenComplete((value, failure) -> {
                if (!counted) {
                    return;
                }
                if (failure != null) {
                    failed[0]++;
                } else {
                    latencies[measured[0]++] = clock.read() - start;
                }
            });
        }
        // not advanceUntilIdle: an overloaded run ends with a backlog longer than its 100,000-task guard
        for (Duration next = clock.nextTaskIn(); next != null; next = clock.nextTaskIn()) {
            clock.advance(next);
        }
        long elapsed = clock.read();
        assertThat(measured[0] + failed[0]).as("every call ended").isEqualTo(calls - warmUp);
        long[] done = Arrays.copyOf(latencies, measured[0]);
        Arrays.sort(done);
        return new Result(setup, load, cancelMode, calls, millis(done, 0.50), millis(done, 0.90), millis(done, 0.99),
                millis(done, 0.999), done.length == 0 ? 0 : done[done.length - 1] / 1e6,
                (double) downstream.requests() / calls, 100.0 * downstream.busyNanos() / ((double) WORKERS * elapsed),
                100.0 * downstream.wastedNanos() / Math.max(1, downstream.busyNanos()),
                100.0 * failed[0] / (calls - warmUp));
    }

    private static double millis(long[] sorted, double quantile) {
        if (sorted.length == 0) {
            return 0;
        }
        int index = (int) Math.ceil(quantile * sorted.length) - 1;
        return sorted[Math.max(0, index)] / 1e6;
    }

    @Test
    void sameSeedSameResult() {
        assertThat(run(Setup.HEDGE_BUDGET_ADAPTIVE, 0.7, CancelMode.IGNORED, 20_000, 3))
                .isEqualTo(run(Setup.HEDGE_BUDGET_ADAPTIVE, 0.7, CancelMode.IGNORED, 20_000, 3));
    }

    @Test
    void sweep() throws IOException {
        int calls = Integer.getInteger("test.hedge.calls", 200_000);
        long seed = Long.getLong("test.hedge.seed", 1L);
        List<Result> results = new ArrayList<>();
        for (CancelMode mode : CancelMode.values()) {
            for (double load : LOADS) {
                for (Setup setup : Setup.values()) {
                    Result result = run(setup, load, mode, calls, seed);
                    results.add(result);
                    if (setup == Setup.HEDGE_DEFAULT_BUDGET || setup == Setup.HEDGE_BUDGET_ADAPTIVE) {
                        // 10 % of the calls plus 1 hedge per second of the run; the budget errs low, never high
                        double seconds = estimatedSeconds(calls, load);
                        assertThat(result.requestsPerCall())
                                .as("%s at %.0f %%: the budget caps the hedges", setup.label, load * 100)
                                .isLessThanOrEqualTo(1.10 + (seconds + 10) / calls);
                    }
                }
            }
        }
        String report = report(results, calls, seed);
        System.out.println(report);
        Path dir = Path.of(System.getProperty("test.hedge.reportDir", "build/hedge-sim"));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("report.md"), report);
    }

    /** How long the arrivals of a run last, in seconds (an estimate: the mean gap times the calls). */
    private static double estimatedSeconds(int calls, double load) {
        double meanService = ServiceTime.standard(0).meanNanos(0, 200_000);
        return calls * meanService / (WORKERS * load) / 1e9;
    }

    static String report(List<Result> results, int calls, long seed) {
        StringBuilder out = new StringBuilder();
        out.append("# Hedging under contention (simulation)\n\n");
        out.append(String.format(Locale.ROOT, "%,d calls per point (first %.0f %% not measured), seed %d, %d workers,"
                + " hedge delay %d ms. Service time: log-normal, median 10 ms, sigma 0.25; 2 %% of requests take"
                + " 100-300 ms instead.%n%n", calls, WARM_UP * 100, seed, WORKERS, DELAY.toMillis()));
        for (CancelMode mode : CancelMode.values()) {
            out.append("## Cancellation ").append(mode == CancelMode.IGNORED
                    ? "ignored by the server (losers keep their worker)" : "reaches the server (losers free their worker)")
                    .append("\n\n");
            out.append("| Load without hedges | Setup | p50 ms | p90 ms | p99 ms | p99.9 ms | max ms | requests/call |"
                    + " worker busy % | wasted work % | failed % |\n");
            out.append("|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
            for (Result r : results) {
                if (r.cancelMode() != mode) {
                    continue;
                }
                out.append(String.format(Locale.ROOT,
                        "| %.0f %% | %s | %.1f | %.1f | %.1f | %.1f | %.1f | %.3f | %.1f | %.1f | %.2f |%n",
                        r.load() * 100, r.setup().label, r.p50(), r.p90(), r.p99(), r.p999(), r.max(),
                        r.requestsPerCall(), r.utilization(), r.wastedShare(), r.failedShare()));
            }
            out.append('\n');
        }
        return out.toString();
    }
}
