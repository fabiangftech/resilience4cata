package dev.resilience4cata.nativeimage.plainjava;

import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** {@code Mono} and {@code Flux} methods of an annotated class, through the reactor module. */
public class Quotes {

    public final AtomicInteger subscriptions = new AtomicInteger();

    @Retry(maxAttempts = 3, delay = "1ms")
    public Mono<String> rate(String currency) {
        return Mono.fromCallable(() -> {
            if (subscriptions.incrementAndGet() < 3) {
                throw new IllegalStateException("rates down");
            }
            return currency + "=1.1";
        });
    }

    @Retry(maxAttempts = 2, delay = "1ms")
    @Fallback(method = "noTicks")
    public Flux<String> ticks(String symbol) {
        return Flux.error(new IllegalStateException("feed down"));
    }

    public Flux<String> noTicks(String symbol, Exception e) {
        return Flux.just(symbol + ": closed");
    }
}
