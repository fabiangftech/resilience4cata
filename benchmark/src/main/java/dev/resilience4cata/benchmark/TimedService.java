package dev.resilience4cata.benchmark;

import dev.resilience4cata.annotation.Timeout;

/** Service protected by a timeout, for {@link TimeoutBenchmark}. */
public interface TimedService {

    @Timeout("1s")
    String call();

    /** Thread-safe implementation with trivial work. */
    final class Impl implements TimedService {
        private final String value = "ok";

        @Override
        public String call() {
            return value;
        }
    }
}
