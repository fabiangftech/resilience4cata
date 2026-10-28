package dev.resilience4cata.spring.reactive;

import dev.resilience4cata.annotation.CircuitBreaker;
import reactor.core.publisher.Mono;

/** An annotated interface with a {@code Mono} method: its implementation bean is wrapped. */
public interface RatesClient {

    @CircuitBreaker(name = "rates", windowSize = 2, minimumCalls = 2, failureRateThreshold = 50, waitInOpen = "1h")
    Mono<String> rate(String currency);
}
