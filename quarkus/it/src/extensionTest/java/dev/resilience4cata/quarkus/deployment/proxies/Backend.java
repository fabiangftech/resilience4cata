package dev.resilience4cata.quarkus.deployment.proxies;

import jakarta.inject.Singleton;
import java.util.concurrent.atomic.AtomicInteger;

/** What the protected beans call: fails as often as a test says, and counts the calls. */
@Singleton
public class Backend {

    public final AtomicInteger calls = new AtomicInteger();
    public final AtomicInteger failures = new AtomicInteger();

    public String call(String id) {
        calls.incrementAndGet();
        if (failures.getAndDecrement() > 0) {
            throw new IllegalStateException("down");
        }
        return "ok " + id;
    }
}
