package dev.resilience4cata.quarkus.deployment.fixtures;

import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

// tag::quarkus-reactive[]
@ApplicationScoped
public class PriceService {

    @Inject
    PriceBackend backend;

    @Retry(maxAttempts = 3, delay = "10ms")
    @Timeout("2s")
    public Mono<Integer> price(String sku) {
        return backend.price(sku);
    }

    @Retry(maxAttempts = 3, delay = "10ms")
    @Timeout("2s")                                  // to the first element, and between two
    @Fallback(method = "noTicks")
    public Flux<Integer> ticks(String sku) {
        return backend.ticks(sku);
    }

    protected Flux<Integer> noTicks(String sku) {
        return Flux.empty();
    }
}
// end::quarkus-reactive[]
