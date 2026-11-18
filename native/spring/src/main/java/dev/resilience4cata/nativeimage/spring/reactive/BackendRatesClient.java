package dev.resilience4cata.nativeimage.spring.reactive;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The implementation that is wrapped with {@code RatesClient$Resilient}. */
@Component
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
