package dev.resilience4cata.examples.docs.reactor;

import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import java.math.BigDecimal;
import reactor.core.publisher.Flux;

// tag::reactor-flux-interface[]
public interface PriceFeed {

    @Retry(maxAttempts = 3, delay = "50ms")
    @Timeout("5s")                       // at most 5 s to the first price, and between prices
    @Fallback(method = "closed")
    Flux<BigDecimal> prices(String sku);

    default Flux<BigDecimal> closed(String sku, Throwable failure) {
        return Flux.empty();
    }
}
// end::reactor-flux-interface[]
