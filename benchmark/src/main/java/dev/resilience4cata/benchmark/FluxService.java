package dev.resilience4cata.benchmark;

import dev.resilience4cata.annotation.Bulkhead;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.RateLimiter;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import reactor.core.publisher.Flux;

/**
 * Methods that return {@code Flux}, one per policy plus a stack of four, for {@link FluxBenchmark}. Each
 * returns the {@code Flux} it is given, so the benchmark decides how many elements there are and when
 * they come.
 */
public interface FluxService {

    @CircuitBreaker
    Flux<String> circuitBreaker(Flux<String> source);

    @Retry(maxAttempts = 3)
    Flux<String> retry(Flux<String> source);

    @Timeout("1s")
    Flux<String> timeout(Flux<String> source);

    @Bulkhead(maxConcurrent = 1000)
    Flux<String> bulkhead(Flux<String> source);

    @RateLimiter(permits = 1_000_000_000, period = "1s")
    Flux<String> rateLimiter(Flux<String> source);

    @Retry(maxAttempts = 3)
    @CircuitBreaker
    @Timeout("1s")
    @Bulkhead(maxConcurrent = 1000)
    Flux<String> stack(Flux<String> source);

    /** Returns the source as it is. */
    final class Impl implements FluxService {
        @Override
        public Flux<String> circuitBreaker(Flux<String> source) {
            return source;
        }

        @Override
        public Flux<String> retry(Flux<String> source) {
            return source;
        }

        @Override
        public Flux<String> timeout(Flux<String> source) {
            return source;
        }

        @Override
        public Flux<String> bulkhead(Flux<String> source) {
            return source;
        }

        @Override
        public Flux<String> rateLimiter(Flux<String> source) {
            return source;
        }

        @Override
        public Flux<String> stack(Flux<String> source) {
            return source;
        }
    }
}
