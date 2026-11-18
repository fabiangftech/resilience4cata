package dev.resilience4cata.nativeimage.micronaut.reactive;

import jakarta.inject.Singleton;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The implementation, exposed as {@code RatesClient}: native:micronaut's BackendRatesClient without {@code @ResilientBean}. */
@Singleton
public class PlainBackendRatesClient implements RatesClient {

    private final QuoteBackend backend;

    public PlainBackendRatesClient(QuoteBackend backend) {
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
