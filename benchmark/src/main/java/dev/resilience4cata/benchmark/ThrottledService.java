package dev.resilience4cata.benchmark;

import dev.resilience4cata.annotation.RateLimiter;

/** Service protected by rate limiters, for {@link RateLimiterBenchmark}. */
public interface ThrottledService {

    @RateLimiter(config = "bench")
    String call();

    @RateLimiter(name = "exhausted", config = "bench-exhausted")
    String callExhausted();

    /** Thread-safe implementation with trivial work. */
    final class Impl implements ThrottledService {
        private final String value = "ok";

        @Override
        public String call() {
            return value;
        }

        @Override
        public String callExhausted() {
            return value;
        }
    }
}
