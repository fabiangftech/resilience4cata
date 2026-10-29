package dev.resilience4cata.examples;

import dev.resilience4cata.annotation.Backoff;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Flux;

/** An annotated class whose methods return {@code Flux}, for {@link FluxEndToEndTest}. */
public class FluxService {

    final AtomicInteger calls = new AtomicInteger();
    volatile int failures;

    @Retry(name = "flux-service", maxAttempts = 3, delay = "100ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    @Fallback(method = "nothing")
    public Flux<String> pages(String id) {
        calls.incrementAndGet();
        return Flux.defer(() -> failures-- > 0 ? Flux.error(new IllegalStateException("down"))
                : Flux.just(id + "-1", id + "-2"));
    }

    protected Flux<String> nothing(String id) {
        return Flux.just("no pages for " + id);
    }

    /** Calls {@link #pages} on {@code this}: the generated subclass's override, so with the policies. */
    public Flux<String> firstPages(String id) {
        return pages(id).take(1);
    }
}
