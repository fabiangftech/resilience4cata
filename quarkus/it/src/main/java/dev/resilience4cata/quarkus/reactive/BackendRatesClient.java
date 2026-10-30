package dev.resilience4cata.quarkus.reactive;

import jakarta.inject.Singleton;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The implementation that is wrapped with {@code RatesClient$Resilient}. */
@Singleton
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
