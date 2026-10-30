package dev.resilience4cata.micronaut.proxies;

import dev.resilience4cata.annotation.Retry;
import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A scheduled, retried method of an annotated class: the scheduler invokes the generated override. Its own counters,
 * since it runs in the background of every test of its context.
 */
@Singleton
@Requires(env = "proxies")
public class ScheduledSync {

    public final AtomicInteger runs = new AtomicInteger();
    public final AtomicInteger attempts = new AtomicInteger();
    public final AtomicInteger failures = new AtomicInteger();

    @Scheduled(fixedDelay = "50ms")
    @Retry(maxAttempts = 3, delay = "1ms")
    public void sync() {
        attempts.incrementAndGet();
        if (failures.getAndDecrement() > 0) {
            throw new IllegalStateException("down");
        }
        runs.incrementAndGet();
    }
}
