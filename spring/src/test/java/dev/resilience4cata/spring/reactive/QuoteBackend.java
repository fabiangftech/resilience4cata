package dev.resilience4cata.spring.reactive;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** What the protected beans call: fails as often as a test says, and counts calls and cancellations. */
@Component
public class QuoteBackend {

    final AtomicInteger calls = new AtomicInteger();
    final AtomicInteger cancellations = new AtomicInteger();
    final AtomicInteger failures = new AtomicInteger();
    volatile boolean hang;
    /** Streams stop after their first tick and never end (an idle timeout). */
    volatile boolean stall;
    /** Streams fail after two ticks (after the first element: not retried). */
    volatile boolean breakAfterTwo;

    /** A quote that includes the request id from the Reactor context, to show the context reached every attempt. */
    Mono<String> quote(String sku) {
        return Mono.deferContextual(context -> {
            calls.incrementAndGet();
            if (hang) {
                return Mono.<String>never().doOnCancel(cancellations::incrementAndGet);
            }
            if (failures.getAndDecrement() > 0) {
                return Mono.error(new IOException("backend down"));
            }
            return Mono.just(sku + "=42 for " + context.getOrDefault("requestId", "?"));
        });
    }

    /** Three ticks 20 ms apart, each with the request id from the Reactor context. */
    Flux<String> ticks(String sku) {
        return Flux.deferContextual(context -> {
            calls.incrementAndGet();
            if (hang) {
                return Flux.<String>never().doOnCancel(cancellations::incrementAndGet);
            }
            if (failures.getAndDecrement() > 0) {
                return Flux.error(new IOException("backend down"));
            }
            Flux<String> ticks = Flux.range(1, 3)
                    .map(k -> sku + " tick " + k + " for " + context.getOrDefault("requestId", "?"))
                    .delayElements(Duration.ofMillis(20));
            if (stall) {
                return ticks.take(1).concatWith(Flux.never()).doOnCancel(cancellations::incrementAndGet);
            }
            return breakAfterTwo ? ticks.take(2).concatWith(Flux.error(new IOException("feed broke"))) : ticks;
        });
    }
}
