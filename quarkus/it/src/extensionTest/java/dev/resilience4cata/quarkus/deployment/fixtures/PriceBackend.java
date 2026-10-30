package dev.resilience4cata.quarkus.deployment.fixtures;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** What {@link PriceService} calls: fails as often as a test says, then answers. */
@ApplicationScoped
public class PriceBackend {

    private final AtomicInteger calls = new AtomicInteger();
    private volatile int failures;

    public int calls() {
        return calls.get();
    }

    public void failNext(int times) {
        failures = times;
    }

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
