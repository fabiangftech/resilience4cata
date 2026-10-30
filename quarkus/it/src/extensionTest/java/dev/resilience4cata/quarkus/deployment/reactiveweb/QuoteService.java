package dev.resilience4cata.quarkus.deployment.reactiveweb;

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

    @Retry(name = "web-quotes", maxAttempts = 3, delay = "5ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    @Timeout("2s")
    public Mono<String> quote(String sku) {
        return backend.quote(sku);
    }

    @Retry(name = "web-quotes-fallback", maxAttempts = 2, delay = "5ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    @Fallback(method = "cached")
    public Mono<String> quoteOrCached(String sku) {
        return backend.quote(sku);
    }

    protected Mono<String> cached(String sku, Throwable failure) {
        return Mono.just(sku + "=40 (cached)");
    }

    @Timeout("200ms")
    @Bulkhead(name = "web-slow-quotes", maxConcurrent = 1)
    public Mono<String> slowQuote(String sku) {
        return backend.quote(sku);
    }

    /** One tick, then nothing: open until the subscriber cancels (no timeout). */
    @Bulkhead(name = "web-feed", maxConcurrent = 1)
    public Flux<String> feed(String sku) {
        return backend.feed(sku);
    }

    @Retry(name = "web-ticks", maxAttempts = 3, delay = "5ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    @Timeout("300ms")
    @Bulkhead(name = "web-ticks", maxConcurrent = 1)
    public Flux<String> ticks(String sku) {
        return backend.ticks(sku);
    }
}
