package dev.resilience4cata.benchmark;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Bulkhead;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.RateLimiter;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;

/** The generated-code side of {@link PipelineBenchmark}; the named configs are registered there. */
public interface PipelineService {

    @Retry(config = "bench")
    @CircuitBreaker(config = "bench")
    String retryCircuitBreaker();

    @Retry(config = "bench")
    @CircuitBreaker(config = "bench")
    String echo(String value);

    @Retry(config = "bench")
    @CircuitBreaker(config = "bench")
    @RateLimiter(config = "bench")
    @Bulkhead(config = "bench")
    String four();

    @Fallback(Constant.class)
    @Retry(config = "bench")
    @CircuitBreaker(config = "bench")
    @RateLimiter(config = "bench")
    @Timeout(config = "bench")
    @Bulkhead(config = "bench")
    String all();

    /** Fallback of {@link #all()}; never called in the benchmark, where every call succeeds. */
    final class Constant implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "fallback";
        }
    }

    /** Thread-safe implementation with trivial work. */
    final class Impl implements PipelineService {
        private final String value = "ok";

        public String call() {
            return value;
        }

        @Override
        public String retryCircuitBreaker() {
            return value;
        }

        @Override
        public String echo(String value) {
            return value;
        }

        @Override
        public String four() {
            return value;
        }

        @Override
        public String all() {
            return value;
        }
    }
}
