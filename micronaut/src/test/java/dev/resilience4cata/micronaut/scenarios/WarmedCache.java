package dev.resilience4cata.micronaut.scenarios;

import dev.resilience4cata.annotation.Retry;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicInteger;

/** Created with the context, before any lookup, and calls a protected method of its own from {@code @PostConstruct}. */
@Context
@Requires(property = "test.scenarios", value = "context")
public class WarmedCache {

    public final AtomicInteger attempts = new AtomicInteger();
    public volatile String warmed;

    @PostConstruct
    void warm() {
        warmed = load("warm-up");
    }

    /** Fails every odd attempt. */
    @Retry(name = "warmed-cache", maxAttempts = 2, delay = "10ms")
    public String load(String key) {
        if (attempts.incrementAndGet() % 2 == 1) {
            throw new IllegalStateException("cold");
        }
        return "loaded " + key;
    }
}
