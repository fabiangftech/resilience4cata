package dev.resilience4cata.spring.reactive;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** What the protected beans call: fails as often as a test says, and counts calls and cancellations. */
@Component
public class QuoteBackend {

    final AtomicInteger calls = new AtomicInteger();
    final AtomicInteger cancellations = new AtomicInteger();
    final AtomicInteger failures = new AtomicInteger();
    volatile boolean hang;

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
}
