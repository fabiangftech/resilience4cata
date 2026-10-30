package dev.resilience4cata.micronaut.reactiveweb;

import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Retry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** An annotated interface with {@code Mono} and {@code Flux} methods: its {@code @ResilientBean} is wrapped. */
public interface RatesClient {

    @CircuitBreaker(name = "web-rates", windowSize = 2, minimumCalls = 2, failureRateThreshold = 50, waitInOpen = "1h")
    Mono<String> rate(String currency);

    @Retry(name = "web-rate-ticks", maxAttempts = 2, delay = "5ms")
    @Fallback(method = "noTicks")
    Flux<String> rateTicks(String currency);

    default Flux<String> noTicks(String currency) {
        return Flux.just(currency + " closed");
    }
}
