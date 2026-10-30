package dev.resilience4cata.quarkus.reactive;

import dev.resilience4cata.annotation.Backoff;
import dev.resilience4cata.annotation.Bulkhead;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import jakarta.inject.Singleton;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** An annotated class with {@code Mono} and {@code Flux} methods: ArC uses its generated subclass. */
@Singleton
public class QuoteService {

    private final QuoteBackend backend;

    public QuoteService(QuoteBackend backend) {
        this.backend = backend;
    }

    @Retry(name = "smoke-quotes", maxAttempts = 3, delay = "5ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    @Timeout("2s")
    public Mono<String> quote(String sku) {
        return backend.quote(sku);
    }

    @Timeout("100ms")
    @Fallback(method = "late")
    public Mono<String> slowQuote(String sku) {
        return backend.quote(sku);
    }

    protected Mono<String> late(String sku, Throwable failure) {
        return Mono.just(sku + " late: " + failure.getClass().getSimpleName());
    }

    @Retry(name = "smoke-ticks", maxAttempts = 3, delay = "5ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    @Timeout("1s")
    @Bulkhead(name = "smoke-ticks", maxConcurrent = 2)
    public Flux<String> ticks(String sku) {
        return backend.ticks(sku);
    }
}
