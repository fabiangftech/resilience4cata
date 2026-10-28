package dev.resilience4cata.examples;

import dev.resilience4cata.annotation.Backoff;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Mono;

/** An annotated class with {@code Mono} methods, for {@link MonoClassEndToEndTest}. */
public class MonoService {

    final AtomicInteger calls = new AtomicInteger();
    final String prefix;
    int failures;

    public MonoService(String prefix) {
        this.prefix = prefix;
    }

    @Retry(name = "mono-service", maxAttempts = 3, delay = "100ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    @Fallback(method = "unavailable")
    public Mono<String> greet(String name) {
        return Mono.defer(() -> {
            calls.incrementAndGet();
            return failures-- > 0 ? Mono.error(new IOException("down")) : Mono.just(prefix + " " + name);
        });
    }

    protected Mono<String> unavailable(String name, IOException failure) {
        return Mono.just("unavailable for " + name);
    }

    @Timeout("1s")
    public Mono<String> slow(String name) {
        return Mono.never();
    }

    /** Not annotated, calls {@link #greet} on {@code this}: the generated subclass protects that call too. */
    public Mono<String> greetTwice(String name) {
        return greet(name).zipWith(greet(name), (a, b) -> a + " / " + b);
    }
}
