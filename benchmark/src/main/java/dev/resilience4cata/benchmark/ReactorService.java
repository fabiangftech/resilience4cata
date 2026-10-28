package dev.resilience4cata.benchmark;

import dev.resilience4cata.annotation.Bulkhead;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.RateLimiter;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import reactor.core.publisher.Mono;

/**
 * Methods that return {@code Mono}, one per policy plus a stack of four, for {@link ReactorBenchmark}.
 * Each returns the {@code Mono} it is given, so the benchmark decides whether the value is there already
 * or comes later.
 */
public interface ReactorService {

    @CircuitBreaker
    Mono<String> circuitBreaker(Mono<String> source);

    @Retry(maxAttempts = 3)
    Mono<String> retry(Mono<String> source);

    @Timeout("1s")
    Mono<String> timeout(Mono<String> source);

    @Bulkhead(maxConcurrent = 1000)
    Mono<String> bulkhead(Mono<String> source);

    @RateLimiter(permits = 1_000_000_000, period = "1s")
    Mono<String> rateLimiter(Mono<String> source);

    @Retry(maxAttempts = 3)
    @CircuitBreaker
    @Timeout("1s")
    @Bulkhead(maxConcurrent = 1000)
    Mono<String> stack(Mono<String> source);

    /** Returns the source as it is. */
    final class Impl implements ReactorService {
        @Override
        public Mono<String> circuitBreaker(Mono<String> source) {
            return source;
        }

        @Override
        public Mono<String> retry(Mono<String> source) {
            return source;
        }

        @Override
        public Mono<String> timeout(Mono<String> source) {
            return source;
        }

        @Override
        public Mono<String> bulkhead(Mono<String> source) {
            return source;
        }

        @Override
        public Mono<String> rateLimiter(Mono<String> source) {
            return source;
        }

        @Override
        public Mono<String> stack(Mono<String> source) {
            return source;
        }
    }
}
