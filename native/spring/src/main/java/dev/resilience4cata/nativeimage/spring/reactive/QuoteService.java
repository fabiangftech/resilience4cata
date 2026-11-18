package dev.resilience4cata.nativeimage.spring.reactive;

import dev.resilience4cata.annotation.Backoff;
import dev.resilience4cata.annotation.Bulkhead;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** An annotated class with {@code Mono} and {@code Flux} methods: Spring creates its generated subclass. */
@Service
public class QuoteService {

    private final QuoteBackend backend;

    public QuoteService(QuoteBackend backend) {
        this.backend = backend;
    }

    @Retry(name = "quotes", maxAttempts = 3, delay = "5ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
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

    @Retry(name = "ticks", maxAttempts = 3, delay = "5ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    @Timeout("1s")
    @Bulkhead(name = "ticks", maxConcurrent = 2)
    public Flux<String> ticks(String sku) {
        return backend.ticks(sku);
    }
}
