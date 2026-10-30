package dev.resilience4cata.quarkus.reactive;

import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Retry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** An annotated interface with {@code Mono} and {@code Flux} methods: its implementation bean is wrapped. */
public interface RatesClient {

    @Retry(name = "smoke-rates", maxAttempts = 3, delay = "5ms")
    Mono<String> rate(String currency);

    @Retry(name = "smoke-rate-ticks", maxAttempts = 2, delay = "5ms")
    @Fallback(method = "noTicks")
    Flux<String> rateTicks(String currency);

    default Flux<String> noTicks(String currency) {
        return Flux.just(currency + " closed");
    }
}
