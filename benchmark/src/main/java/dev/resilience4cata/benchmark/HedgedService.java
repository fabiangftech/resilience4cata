package dev.resilience4cata.benchmark;

import dev.resilience4cata.annotation.Hedge;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Hedged asynchronous service for {@link HedgeBenchmark}. Every attempt calls the delegate again. */
public interface HedgedService {

    /** Returns an already completed future: the hedge needs no timer. */
    @Hedge(delay = "50ms")
    CompletableFuture<String> done();

    /** Returns {@code stage}, which the caller completes right after the call: the delay is armed and cancelled. */
    @Hedge(delay = "50ms")
    CompletableFuture<String> pending(CompletableFuture<String> stage);

    /** Same as {@link #pending}, without a hedge budget. */
    @Hedge(delay = "50ms", budgetPercent = 0)
    CompletableFuture<String> pendingNoBudget(CompletableFuture<String> stage);

    /**
     * Each attempt takes its stage from {@code attempts}: the benchmark's supplier returns a pending future
     * for the first attempt and a completed one for the hedge, so the hedge fires after 1 ms and wins.
     */
    @Hedge(delay = "1ms", budgetPercent = 0)
    CompletableFuture<String> slowFirst(Supplier<CompletableFuture<String>> attempts);

    /** Returns what it is given. */
    final class Impl implements HedgedService {
        private final CompletableFuture<String> done = CompletableFuture.completedFuture("ok");

        @Override
        public CompletableFuture<String> done() {
            return done;
        }

        @Override
        public CompletableFuture<String> pending(CompletableFuture<String> stage) {
            return stage;
        }

        @Override
        public CompletableFuture<String> pendingNoBudget(CompletableFuture<String> stage) {
            return stage;
        }

        @Override
        public CompletableFuture<String> slowFirst(Supplier<CompletableFuture<String>> attempts) {
            return attempts.get();
        }
    }
}
