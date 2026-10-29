package dev.resilience4cata.examples.docs.reactor;

import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import java.math.BigDecimal;
import reactor.core.publisher.Mono;

// tag::reactor-interface[]
public interface PriceClient {

    @Retry(maxAttempts = 3, delay = "50ms")
    @CircuitBreaker(name = "prices")
    @Timeout("2s")
    @Fallback(method = "unknown")
    Mono<BigDecimal> price(String sku);

    default Mono<BigDecimal> unknown(String sku, Throwable failure) {
        return Mono.just(BigDecimal.ZERO);
    }
}
// end::reactor-interface[]
