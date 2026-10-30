package dev.resilience4cata.spring.load;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * The dependency the protected beans call: takes {@code latencyMillis}, fails while {@code failing}, and records how
 * many calls ran at once, how many there were, and how many were interrupted.
 */
@Component
public class Warehouse {

    final AtomicInteger calls = new AtomicInteger();
    final AtomicInteger inside = new AtomicInteger();
    final AtomicInteger maxInside = new AtomicInteger();
    final AtomicInteger interrupted = new AtomicInteger();
    final Map<String, AtomicInteger> attemptsPerId = new ConcurrentHashMap<>();
    volatile long latencyMillis;
    volatile boolean failing;
    /** Fails the first attempt for every id, then answers. */
    volatile boolean failFirstAttempt;

    void reset() {
        calls.set(0);
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
