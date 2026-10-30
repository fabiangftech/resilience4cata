package dev.resilience4cata.micronaut.load;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The dependency the protected beans call: takes {@code latencyMillis}, fails while {@code failing}, and records how
 * many calls ran at once, how many there were, and how many were interrupted.
 */
@Singleton
@Requires(env = "load")
public class Warehouse {

    final AtomicInteger calls = new AtomicInteger();
    /** Calls that ran on a virtual thread. */
    final AtomicInteger virtualCalls = new AtomicInteger();
    final AtomicInteger inside = new AtomicInteger();
    final AtomicInteger maxInside = new AtomicInteger();
    final AtomicInteger interrupted = new AtomicInteger();
    final Map<String, AtomicInteger> attemptsPerId = new ConcurrentHashMap<>();
    volatile long latencyMillis;
    volatile boolean failing;
    volatile boolean failFirstAttempt;

    void reset() {
        calls.set(0);
        virtualCalls.set(0);
        inside.set(0);
        maxInside.set(0);
        interrupted.set(0);
        attemptsPerId.clear();
        latencyMillis = 0;
        failing = false;
        failFirstAttempt = false;
    }

    int work(String id) {
        calls.incrementAndGet();
        if (Thread.currentThread().isVirtual()) {
            virtualCalls.incrementAndGet();
        }
        int attempt = attemptsPerId.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
        maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
        try {
            if (latencyMillis > 0) {
                Thread.sleep(latencyMillis);
            }
        } catch (InterruptedException e) {
            interrupted.incrementAndGet();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        } finally {
            inside.decrementAndGet();
        }
        if (failing || failFirstAttempt && attempt == 1) {
            throw new IllegalStateException("warehouse down");
        }
        return attempt;
    }
}
