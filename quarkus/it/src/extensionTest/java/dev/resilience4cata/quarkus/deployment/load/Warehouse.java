package dev.resilience4cata.quarkus.deployment.load;

import jakarta.inject.Singleton;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The dependency the protected beans call: takes {@code latencyMillis}, fails while {@code failing}, and records how
 * many calls ran at once, how many there were, and how many were interrupted.
 */
@Singleton
public class Warehouse {

    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger virtualCalls = new AtomicInteger();
    private final AtomicInteger inside = new AtomicInteger();
    private final AtomicInteger maxInside = new AtomicInteger();
    private final AtomicInteger interrupted = new AtomicInteger();
    private final Map<String, AtomicInteger> attemptsPerId = new ConcurrentHashMap<>();
    private volatile long latencyMillis;
    private volatile boolean failing;
    private volatile boolean failFirstAttempt;

    public void reset() {
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

    public void latency(long millis) {
        latencyMillis = millis;
    }

    public void failing(boolean failing) {
        this.failing = failing;
    }

    public void failFirstAttempt() {
        failFirstAttempt = true;
    }

    public int calls() {
        return calls.get();
    }

    /** Calls that ran on a virtual thread. */
    public int virtualCalls() {
        return virtualCalls.get();
    }

    public int inside() {
        return inside.get();
    }

    public int maxInside() {
        return maxInside.get();
    }

    public int interrupted() {
        return interrupted.get();
    }

    public Collection<AtomicInteger> attemptsPerId() {
        return attemptsPerId.values();
    }

    public int work(String id) {
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
