package dev.resilience4cata.quarkus.deployment.reactiveweb;

import jakarta.inject.Singleton;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** What the protected beans call: fails as often as a test says, and counts calls and cancellations. */
@Singleton
public class QuoteBackend {

    public final AtomicInteger calls = new AtomicInteger();
    public final AtomicInteger cancellations = new AtomicInteger();
    public final AtomicInteger failures = new AtomicInteger();
    public volatile boolean hang;
    /** Streams stop after their first tick and never end (an idle timeout). */
    public volatile boolean stall;
    /** Streams fail after two ticks (after the first element: not retried). */
    public volatile boolean breakAfterTwo;

    public void reset() {
        calls.set(0);
        cancellations.set(0);
        failures.set(0);
        hang = false;
        stall = false;
        breakAfterTwo = false;
    }

    Mono<String> quote(String sku) {
        return Mono.defer(() -> {
            calls.incrementAndGet();
            if (hang) {
                return Mono.<String>never().doOnCancel(cancellations::incrementAndGet);
            }
            if (failures.getAndDecrement() > 0) {
                return Mono.error(new IOException("backend down"));
            }
            return Mono.just(sku + "=42");
        });
    }

    /** One tick, then nothing until cancelled. */
    Flux<String> feed(String sku) {
        return Flux.defer(() -> {
            calls.incrementAndGet();
            return Flux.just(sku + " tick 1").concatWith(Flux.never()).doOnCancel(cancellations::incrementAndGet);
        });
    }

    /** Three ticks 20 ms apart. */
    Flux<String> ticks(String sku) {
        return Flux.defer(() -> {
            calls.incrementAndGet();
            if (hang) {
                return Flux.<String>never().doOnCancel(cancellations::incrementAndGet);
            }
            if (failures.getAndDecrement() > 0) {
                return Flux.error(new IOException("backend down"));
            }
            Flux<String> ticks = Flux.range(1, 3).map(k -> sku + " tick " + k).delayElements(Duration.ofMillis(20));
            if (stall) {
                return ticks.take(1).concatWith(Flux.never()).doOnCancel(cancellations::incrementAndGet);
            }
            return breakAfterTwo ? ticks.take(2).concatWith(Flux.error(new IOException("feed broke"))) : ticks;
        });
    }
}
