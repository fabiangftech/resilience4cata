package dev.resilience4cata.benchmark;

import dev.resilience4cata.annotation.Bulkhead;

/** Service protected by bulkheads, for {@link BulkheadBenchmark}. */
public interface LimitedService {

    @Bulkhead(config = "bench")
    String call();

    @Bulkhead(config = "bench-wait")
    String callWaiting();

    @Bulkhead(name = "full", config = "bench-full")
    String callFull();

    /** Thread-safe implementation with trivial work. */
    final class Impl implements LimitedService {
        private final String value = "ok";

        @Override
        public String call() {
            return value;
        }

        @Override
        public String callWaiting() {
            return value;
        }

        @Override
        public String callFull() {
            return value;
        }
    }
}
