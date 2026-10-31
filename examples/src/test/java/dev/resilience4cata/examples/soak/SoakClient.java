package dev.resilience4cata.examples.soak;

import dev.resilience4cata.annotation.AdaptiveLimit;
import dev.resilience4cata.annotation.Backoff;
import dev.resilience4cata.annotation.Bulkhead;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Hedge;
import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.RateLimiter;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Every policy, sync and async, with short timeouts and small limits so that each path runs often under load:
 * retries, breakers that open and close again, rejections, timeouts that interrupt or cancel, bulkhead hand-offs,
 * hedges, adaptive limits moving. For {@link SoakTest}.
 */
public interface SoakClient {

    @Retry(name = "soak-sync", maxAttempts = 3, delay = "1ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    @CircuitBreaker(name = "soak-sync", windowSize = 50, minimumCalls = 20, failureRateThreshold = 60,
            waitInOpen = "20ms")
    @RateLimiter(name = "soak-sync", permits = 20_000, period = "1s")
    @Timeout("20ms")
    @Bulkhead(name = "soak-sync", maxConcurrent = 6, maxWait = "2ms")
    @Fallback(method = "recover")
    String sync(int i) throws IOException;

    @AdaptiveLimit(name = "soak-adaptive", initialLimit = 8, minLimit = 2, maxLimit = 32, window = "10ms")
    @Timeout("20ms")
    String adaptive(int i) throws IOException;

    @Retry(name = "soak-async", maxAttempts = 3, delay = "1ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    @CircuitBreaker(name = "soak-async", windowSize = 50, minimumCalls = 20, failureRateThreshold = 60,
            waitInOpen = "20ms")
    @Timeout("20ms")
    @Bulkhead(name = "soak-async", maxConcurrent = 16, maxWait = "5ms")
    CompletableFuture<String> async(int i);

    @Hedge(name = "soak-hedge", maxAttempts = 3, delay = "2ms", budgetPercent = 50)
    @Timeout("30ms")
    @Bulkhead(name = "soak-hedge", maxConcurrent = 48)
    CompletableFuture<String> hedged(int i);

    @Retry(name = "soak-mono", maxAttempts = 3, delay = "1ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    @CircuitBreaker(name = "soak-mono", windowSize = 50, minimumCalls = 20, failureRateThreshold = 60,
            waitInOpen = "20ms")
    @AdaptiveLimit(name = "soak-mono", initialLimit = 32, minLimit = 8, maxLimit = 64, window = "10ms")
    @Timeout("15ms")
    @Bulkhead(name = "soak-mono", maxConcurrent = 16, maxWait = "5ms")
    @Fallback(method = "recoverMono")
    Mono<String> mono(int i);

    @Retry(name = "soak-flux", maxAttempts = 3, delay = "1ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    @CircuitBreaker(name = "soak-flux", windowSize = 50, minimumCalls = 20, failureRateThreshold = 60,
            waitInOpen = "20ms", slowCallDuration = "10ms")
    @Timeout("15ms")
    @Bulkhead(name = "soak-flux", maxConcurrent = 16, maxWait = "5ms")
    @Fallback(method = "recoverFlux")
    Flux<String> flux(int i);

    /** Only for backend failures: rejections and timeouts reach the caller. */
    default String recover(int i, IOException failure) {
        return "recovered:" + i;
    }

    default Mono<String> recoverMono(int i, IOException failure) {
        return Mono.just("recovered:" + i);
    }

    default Flux<String> recoverFlux(int i, IOException failure) {
        return Flux.just("recovered:" + i);
    }
}
