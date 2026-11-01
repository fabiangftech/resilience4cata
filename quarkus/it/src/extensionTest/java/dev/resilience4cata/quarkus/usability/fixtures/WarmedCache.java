package dev.resilience4cata.quarkus.usability.fixtures;

import dev.resilience4cata.annotation.Retry;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.atomic.AtomicInteger;

/** A {@code @Startup} annotated class whose {@code @PostConstruct} calls one of its own protected methods. */
@Startup
@ApplicationScoped
public class WarmedCache {

    /** How many instances were initialized: static, so a test can read it without creating one. */
    public static final AtomicInteger INITIALIZED = new AtomicInteger();
    public static volatile String initializedAs;

    private final AtomicInteger attempts = new AtomicInteger();
    private volatile String warmed;

    @PostConstruct
    void warm() {
        INITIALIZED.incrementAndGet();
        initializedAs = getClass().getName();
        warmed = load("warm-up");
    }

    /** Fails every odd attempt. */
    @Retry(name = "warmed-cache", maxAttempts = 2, delay = "0ms")
    public String load(String key) {
        if (attempts.incrementAndGet() % 2 == 1) {
            throw new IllegalStateException("cold");
        }
        return "loaded " + key;
    }

    public String warmed() {
        return warmed;
    }

    public int attempts() {
        return attempts.get();
    }
}
