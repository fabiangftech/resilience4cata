package dev.resilience4cata.micronaut.reactive;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** What {@link PriceService} calls: fails as often as a test says, then answers. */
@Requires(env = "reactive")
@Singleton
public class PriceBackend {

    final AtomicInteger calls = new AtomicInteger();
    volatile int failures;

    Mono<Integer> price(String sku) {
        return Mono.defer(() -> {
            calls.incrementAndGet();
            return failures-- > 0 ? Mono.error(new IllegalStateException("down")) : Mono.just(42);
        });
    }

    Flux<Integer> ticks(String sku) {
        return Flux.defer(() -> {
            calls.incrementAndGet();
            return failures-- > 0 ? Flux.error(new IllegalStateException("down")) : Flux.just(41, 42, 43);
        });
    }
}
