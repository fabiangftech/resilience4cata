package dev.resilience4cata.quarkus.reactive;

import jakarta.inject.Singleton;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** What the protected beans call: fails as often as an endpoint says, or never answers. */
@Singleton
public class QuoteBackend {

    final AtomicInteger calls = new AtomicInteger();
    final AtomicInteger failures = new AtomicInteger();
    volatile boolean hang;

    /** Prepares the next calls: how many fail first, and whether they hang instead. */
    void prepare(int failures, boolean hang) {
        calls.set(0);
        this.failures.set(failures);
        this.hang = hang;
    }

    Mono<String> quote(String sku) {
        return Mono.defer(() -> {
            calls.incrementAndGet();
            if (hang) {
                return Mono.never();
            }
            if (failures.getAndDecrement() > 0) {
                return Mono.error(new IOException("backend down"));
            }
            return Mono.just(sku + "=42");
        });
    }

    /** Three ticks 20 ms apart, or a failure before the first. */
    Flux<String> ticks(String sku) {
        return Flux.defer(() -> {
            calls.incrementAndGet();
            if (hang) {
                return Flux.never();
            }
            if (failures.getAndDecrement() > 0) {
                return Flux.error(new IOException("backend down"));
            }
            return Flux.range(1, 3).map(k -> sku + " tick " + k).delayElements(Duration.ofMillis(20));
        });
    }
}
