package dev.resilience4cata.benchmark;

import dev.resilience4cata.annotation.CircuitBreaker;

/** Service protected by a circuit breaker, for {@link CircuitBreakerBenchmark}. */
public interface GuardedService {

    @CircuitBreaker(config = "bench")
    String call();

    @CircuitBreaker(config = "bench")
    String maybeFail(boolean fail);

    @CircuitBreaker(config = "bench-time")
    String callTime();

    @CircuitBreaker(config = "bench-time")
    String maybeFailTime(boolean fail);

    /** Thread-safe implementation with trivial work. */
    final class Impl implements GuardedService {
        private final String value = "ok";

        @Override
        public String call() {
            return value;
        }

        @Override
        public String maybeFail(boolean fail) {
            if (fail) {
                throw FakeService.FAILURE;
            }
            return value;
        }

        @Override
        public String callTime() {
            return value;
        }

        @Override
        public String maybeFailTime(boolean fail) {
            if (fail) {
                throw FakeService.FAILURE;
            }
            return value;
        }
    }
}
