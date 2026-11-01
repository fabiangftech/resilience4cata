package dev.resilience4cata.benchmark;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import io.github.resilience4j.hedge.Hedge;
import io.github.resilience4j.hedge.HedgeConfig;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Overhead of a hedge ({@code @Hedge}) on an asynchronous method. Run it with one and four threads
 * ({@code -t 1}, {@code -t 4}): the registry and the hedge instances are shared, so with a budget every
 * thread counts its calls in the same budget (each in its own stripe).
 *
 * <ul>
 *   <li>{@code baselinePending}: the pending future and its completion, without any library.</li>
 *   <li>{@code resilience4cata}: {@code @Hedge(delay = "50ms")}, the delegate returns an already completed
 *       future, so the hedge returns at once without arming its delay (the fast path).</li>
 *   <li>{@code resilience4cataPending}: the delegate returns a future that the benchmark completes right
 *       after the call, so each call arms the delay on the shared timer and cancels it when the first
 *       attempt wins: the realistic path of a hedged call that does not need its hedge.</li>
 *   <li>{@code resilience4cataNoBudget}: the same with {@code budgetPercent = 0}, which isolates the
 *       budget's per-call count.</li>
 *   <li>{@code resilience4j}: Resilience4j's {@code resilience4j-hedge} with
 *       {@code preconfiguredDuration(50ms)}, through {@code decorateCompletionStage}, with the same pending
 *       delegate as {@code resilience4cataPending}.</li>
 *   <li>{@code handWrittenCompletableFuture}: what a user writes without a library: a remove-on-cancel
 *       {@code ScheduledThreadPoolExecutor} arms the hedge, the first success completes the result and
 *       cancels the timer and the other attempt. Same pending delegate.</li>
 *   <li>{@code resilience4cataHedgeFires}, {@code resilience4jHedgeFires}: the first attempt never
 *       completes by itself and the hedge (1 ms delay) succeeds at once; the benchmark waits for the
 *       result. This measures the path where the hedge starts and wins (timer precision, thread start,
 *       the loser's cancel), so the time is dominated by the delay and each library's timer precision.
 *       resilience4cata's hedge delays take their start from the call and the timer wakes up at the
 *       deadline, so both fire at the delay plus the OS's wake-up delay; Resilience4j schedules on its
 *       {@code ScheduledThreadPoolExecutor}. Not part of the allocation gate.</li>
 * </ul>
 *
 * <p>Fairness. Resilience4j's hedge completes with the first attempt to <em>complete</em>, even when it
 * fails, while resilience4cata's completes with the first <em>success</em>; every workload here succeeds,
 * so the two do the same work. Resilience4j runs one hedge only (resilience4cata's default
 * {@code maxAttempts = 2} is the same), has no hedge budget (so compare it with
 * {@code resilience4cataNoBudget} as well), reads {@code System.nanoTime()} twice per call to time the
 * attempt, increments a shared {@code AtomicLong} per call for its metrics, and schedules each delay on its
 * own {@code ScheduledThreadPoolExecutor} (core size {@code concurrentHedges}, 10 by default), which is not
 * remove-on-cancel: a cancelled 50 ms delay stays queued until it would have fired. That is how the
 * library ships (its executor is private), so it is benchmarked unchanged; the hand-written baseline uses a
 * remove-on-cancel executor. Resilience4j has no shortcut for an already completed stage, so it has no
 * counterpart to {@code resilience4cata}. No event listeners on either side.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class HedgeBenchmark {

    private HedgedService direct;
    private HedgedService resilience4cata;
    private Hedge resilience4jHedge;
    private Hedge resilience4jFastHedge;
    private ScheduledThreadPoolExecutor handWrittenTimer;

    /** Builds both libraries with equivalent configuration. */
    @Setup
    public void setup() {
        direct = new HedgedService.Impl();
        resilience4cata = Resilient.of(HedgedService.class, new HedgedService.Impl(), ResilienceRegistry.builder().build());
        resilience4jHedge = Hedge.of(HedgeConfig.custom().preconfiguredDuration(Duration.ofMillis(50)).build());
        resilience4jFastHedge = Hedge.of(HedgeConfig.custom().preconfiguredDuration(Duration.ofMillis(1)).build());
        handWrittenTimer = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().daemon().factory());
        handWrittenTimer.setRemoveOnCancelPolicy(true);
    }

    /** Per benchmark thread: the next stage Resilience4j's decorated suppliers return. */
    @State(Scope.Thread)
    public static class Pending {
        CompletableFuture<String> next;
        Supplier<CompletionStage<String>> resilience4j;
        Supplier<CompletionStage<String>> resilience4jHedgeFires;
        /** The first attempt's stage in the hedge-fires benchmarks; cancelled as the loser. */
        CompletableFuture<String> slow;
        int attempt;
        final Supplier<CompletableFuture<String>> slowThenFast = () -> attempt++ == 0 ? slow : CompletableFuture.completedFuture("ok");

        /** Decorates suppliers that return this thread's next stages. */
        @Setup
        public void setup(HedgeBenchmark benchmark) {
            resilience4j = benchmark.resilience4jHedge.decorateCompletionStage(() -> next);
            resilience4jHedgeFires = benchmark.resilience4jFastHedge.decorateCompletionStage(slowThenFast);
        }

        /** Starts one hedge-fires call: the first attempt gets a new, never completed stage. */
        void slowFirst() {
            slow = new CompletableFuture<>();
            attempt = 0;
        }
    }

    /** Stops the hand-written baseline's timer. Resilience4j's executor threads are daemons of the hedge. */
    @TearDown
    public void tearDown() {
        handWrittenTimer.shutdownNow();
    }

    @Benchmark
    public CompletionStage<String> baselinePending() {
        CompletableFuture<String> stage = new CompletableFuture<>();
        CompletionStage<String> result = direct.pending(stage);
        stage.complete("ok");
        return result;
    }

    @Benchmark
    public CompletionStage<String> resilience4cata() {
        return resilience4cata.done();
    }

    @Benchmark
    public CompletionStage<String> resilience4cataPending() {
        CompletableFuture<String> stage = new CompletableFuture<>();
        CompletionStage<String> result = resilience4cata.pending(stage);
        stage.complete("ok");
        return result;
    }

    @Benchmark
    public CompletionStage<String> resilience4cataNoBudget() {
        CompletableFuture<String> stage = new CompletableFuture<>();
        CompletionStage<String> result = resilience4cata.pendingNoBudget(stage);
        stage.complete("ok");
        return result;
    }

    @Benchmark
    public CompletionStage<String> resilience4j(Pending pending) {
        CompletableFuture<String> stage = new CompletableFuture<>();
        pending.next = stage;
        CompletionStage<String> result = pending.resilience4j.get();
        stage.complete("ok");
        return result;
    }

    @Benchmark
    public CompletionStage<String> handWrittenCompletableFuture() {
        CompletableFuture<String> stage = new CompletableFuture<>();
        CompletionStage<String> result = HandWrittenHedge.hedge(() -> stage, handWrittenTimer, 50_000_000L);
        stage.complete("ok");
        return result;
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public String resilience4cataHedgeFires(Pending pending) {
        pending.slowFirst();
        return resilience4cata.slowFirst(pending.slowThenFast).join();
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public String resilience4jHedgeFires(Pending pending) {
        pending.slowFirst();
        return pending.resilience4jHedgeFires.get().toCompletableFuture().join();
    }

    /**
     * A hand-written hedge of one extra attempt: the first success wins; a failure of the first attempt
     * starts the hedge at once if its timer had not fired yet; the winner cancels the timer and the other
     * attempt. Two failures fail the result with the last one.
     */
    static final class HandWrittenHedge<T> {
        private final Supplier<CompletableFuture<T>> call;
        private final CompletableFuture<T> result = new CompletableFuture<>();
        private final CompletableFuture<T> first;
        private volatile CompletableFuture<T> second;
        private volatile ScheduledFuture<?> timer;

        private HandWrittenHedge(Supplier<CompletableFuture<T>> call) {
            this.call = call;
            this.first = call.get();
        }

        static <T> CompletableFuture<T> hedge(Supplier<CompletableFuture<T>> call, ScheduledThreadPoolExecutor scheduler,
                long delayNanos) {
            HandWrittenHedge<T> hedge = new HandWrittenHedge<>(call);
            hedge.timer = scheduler.schedule(hedge::launch, delayNanos, TimeUnit.NANOSECONDS);
            hedge.first.whenComplete((value, failure) -> {
                if (failure == null) {
                    hedge.win(value, hedge.second);
                } else if (hedge.timer.cancel(false)) {
                    hedge.launch(); // failed before the delay: hedge now
                } else if (hedge.second == null || hedge.second.isCompletedExceptionally()) {
                    hedge.result.completeExceptionally(failure);
                }
            });
            return hedge.result;
        }

        private void launch() {
            if (result.isDone()) {
                return;
            }
            CompletableFuture<T> hedge = call.get();
            second = hedge;
            hedge.whenComplete((value, failure) -> {
                if (failure == null) {
                    win(value, first);
                } else if (first.isCompletedExceptionally()) {
                    result.completeExceptionally(failure);
                }
            });
        }

        private void win(T value, CompletableFuture<T> loser) {
            if (result.complete(value)) {
                timer.cancel(false);
                if (loser != null) {
                    loser.cancel(true);
                }
            }
        }
    }
}
