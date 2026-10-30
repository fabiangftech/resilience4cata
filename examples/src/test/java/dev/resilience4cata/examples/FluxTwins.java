package dev.resilience4cata.examples;

import dev.resilience4cata.annotation.AdaptiveLimit;
import dev.resilience4cata.annotation.Backoff;
import dev.resilience4cata.annotation.Bulkhead;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.RateLimiter;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Pairs of twin methods, for {@link FluxEquivalenceEndToEndTest}: in each shape, {@code mono} and {@code flux}
 * carry the same annotations, so the generated code differs only in returning a {@code Mono} or a {@code Flux}.
 * Every shape a {@code Flux} method may have (no hedge, no classifier, the default order), except a bulkhead
 * with {@code maxWait}: its hand-off runs on a new virtual thread, outside the manual clock, so two identical
 * worlds do not run it in the same order (a {@code Mono} world against another {@code Mono} world differs too).
 */
public final class FluxTwins {

    private FluxTwins() {
    }

    /** What every shape declares; the shapes repeat the two methods with their annotations. */
    public interface Twin {
        Mono<String> mono(int call);

        Flux<String> flux(int call);

        default String recover(int call, Throwable failure) {
            return "fallback:" + failure.getClass().getSimpleName();
        }

        default Mono<String> recoverMono(int call, Throwable failure) {
            return Mono.just("stream fallback:" + failure.getClass().getSimpleName());
        }

        default Flux<String> recoverFlux(int call, Throwable failure) {
            return Flux.just("stream fallback:" + failure.getClass().getSimpleName());
        }
    }

    public interface RetryAndTimeout extends Twin {
        @Override
        @Retry(name = "a", maxAttempts = 3, delay = "10ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
        @Timeout("100ms")
        Mono<String> mono(int call);

        @Override
        @Retry(name = "a", maxAttempts = 3, delay = "10ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
        @Timeout("100ms")
        Flux<String> flux(int call);
    }

    public interface RetryBreakerFallback extends Twin {
        @Override
        @Retry(name = "b", maxAttempts = 2, delay = "10ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
        @CircuitBreaker(name = "b", windowSize = 4, minimumCalls = 2, failureRateThreshold = 50, waitInOpen = "500ms")
        @Fallback(method = "recover")
        Mono<String> mono(int call);

        @Override
        @Retry(name = "b", maxAttempts = 2, delay = "10ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
        @CircuitBreaker(name = "b", windowSize = 4, minimumCalls = 2, failureRateThreshold = 50, waitInOpen = "500ms")
        @Fallback(method = "recover")
        Flux<String> flux(int call);
    }

    public interface SlowCalls extends Twin {
        @Override
        @CircuitBreaker(name = "c", windowSize = 4, minimumCalls = 2, slowCallDuration = "50ms",
                slowCallRateThreshold = 100, waitInOpen = "500ms")
        @Timeout("100ms")
        Mono<String> mono(int call);

        @Override
        @CircuitBreaker(name = "c", windowSize = 4, minimumCalls = 2, slowCallDuration = "50ms",
                slowCallRateThreshold = 100, waitInOpen = "500ms")
        @Timeout("100ms")
        Flux<String> flux(int call);
    }

    public interface FallbackTimeoutBulkhead extends Twin {
        @Override
        @Fallback(method = "recoverMono")
        @Timeout("100ms")
        @Bulkhead(name = "d", maxConcurrent = 1)
        Mono<String> mono(int call);

        @Override
        @Fallback(method = "recoverFlux")
        @Timeout("100ms")
        @Bulkhead(name = "d", maxConcurrent = 1)
        Flux<String> flux(int call);
    }

    public interface LimitedRetry extends Twin {
        @Override
        @Retry(name = "g", maxAttempts = 3, delay = "40ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
        @RateLimiter(name = "g", permits = 2, period = "100ms", maxWait = "60ms")
        @Timeout("100ms")
        Mono<String> mono(int call);

        @Override
        @Retry(name = "g", maxAttempts = 3, delay = "40ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
        @RateLimiter(name = "g", permits = 2, period = "100ms", maxWait = "60ms")
        @Timeout("100ms")
        Flux<String> flux(int call);
    }

    public interface Adaptive extends Twin {
        @Override
        @AdaptiveLimit(name = "h", initialLimit = 2, minLimit = 1, maxLimit = 4, window = "50ms")
        @Timeout("100ms")
        @Fallback(method = "recover")
        Mono<String> mono(int call);

        @Override
        @AdaptiveLimit(name = "h", initialLimit = 2, minLimit = 1, maxLimit = 4, window = "50ms")
        @Timeout("100ms")
        @Fallback(method = "recover")
        Flux<String> flux(int call);
    }
}
