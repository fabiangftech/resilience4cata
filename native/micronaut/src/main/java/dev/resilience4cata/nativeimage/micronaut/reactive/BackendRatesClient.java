package dev.resilience4cata.nativeimage.micronaut.reactive;

import dev.resilience4cata.micronaut.ResilientBean;
import jakarta.inject.Singleton;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The implementation that is wrapped with {@code RatesClient$Resilient}. */
@Singleton
@ResilientBean(RatesClient.class)
public class BackendRatesClient implements RatesClient {

    private final QuoteBackend backend;

    public BackendRatesClient(QuoteBackend backend) {
        this.backend = backend;
    }

    @Override
    public Mono<String> rate(String currency) {
        return backend.quote(currency);
    }

    @Override
    public Flux<String> rateTicks(String currency) {
        return backend.ticks(currency);
    }
}
