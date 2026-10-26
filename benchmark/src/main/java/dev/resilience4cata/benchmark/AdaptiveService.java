package dev.resilience4cata.benchmark;

import dev.resilience4cata.annotation.AdaptiveLimit;
import dev.resilience4cata.annotation.Bulkhead;
import java.util.concurrent.CompletableFuture;

/** Service protected by adaptive limits, and by a fixed bulkhead with the same limit, for {@link AdaptiveLimitBenchmark}. */
public interface AdaptiveService {

    @AdaptiveLimit(config = "bench")
    String call();

    @AdaptiveLimit(name = "full", config = "bench-full")
    String callFull();

    @AdaptiveLimit(config = "bench")
    CompletableFuture<String> callAsync();

    @Bulkhead(maxConcurrent = 1000)
    String callBulkhead();

    /** Thread-safe implementation with trivial work. */
    final class Impl implements AdaptiveService {
        private final String value = "ok";
        private final CompletableFuture<String> done = CompletableFuture.completedFuture(value);

        @Override
        public String call() {
            return value;
        }

        @Override
        public String callFull() {
            return value;
        }

        @Override
        public CompletableFuture<String> callAsync() {
            return done;
        }

        @Override
        public String callBulkhead() {
            return value;
        }
    }
}
