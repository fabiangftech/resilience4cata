package pilot.common;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs inside the protected methods, so it sees what the policies let through: how many calls were inside at once
 * (the bulkhead's limit), how early an interrupted call was interrupted (the timeout), and when the rate-limited calls
 * ran (the limiter's permits).
 */
public final class Probe {

    public static final Probe STOCK = new Probe();
    public static final Probe QUOTE = new Probe();

    private final AtomicInteger inside = new AtomicInteger();
    private final AtomicInteger maxInside = new AtomicInteger();
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong interrupted = new AtomicLong();
    private final AtomicLong minInterruptedNanos = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong firstNanos = new AtomicLong();
    private final AtomicLong lastNanos = new AtomicLong();
    private final java.util.concurrent.ConcurrentHashMap<String, AtomicLong> failures = new java.util.concurrent.ConcurrentHashMap<>();

    /** A backend call. */
    public interface Call {
        String run() throws java.io.IOException, InterruptedException;
    }

    public String call(Call call) throws java.io.IOException, InterruptedException {
        long start = System.nanoTime();
        calls.incrementAndGet();
        firstNanos.compareAndSet(0, start);
        lastNanos.accumulateAndGet(start, Math::max);
        maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
        try {
            return call.run();
        } catch (InterruptedException e) {
            interrupted.incrementAndGet();
            minInterruptedNanos.accumulateAndGet(System.nanoTime() - start, Math::min);
            throw e;
        } catch (java.io.IOException | RuntimeException e) {
            String key = e.getClass().getSimpleName() + ": " + e.getMessage();
            failures.computeIfAbsent(key.length() > 120 ? key.substring(0, 120) : key, k -> new AtomicLong()).incrementAndGet();
            throw e;
        } finally {
            inside.decrementAndGet();
        }
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("calls", calls.get());
        map.put("inside", inside.get());
        map.put("maxInside", maxInside.get());
        map.put("interrupted", interrupted.get());
        long min = minInterruptedNanos.get();
        map.put("minInterruptedMillis", min == Long.MAX_VALUE ? null : min / 1e6);
        long first = firstNanos.get();
        map.put("spanSeconds", first == 0 ? 0 : (lastNanos.get() - first) / 1e9);
        map.put("failures", new java.util.TreeMap<>(failures));
        return map;
    }

    /** The probes, the registry's snapshot and the JVM's numbers, as JSON. */
    public static String stats(Map<String, ?> snapshot) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("stock", STOCK.toMap());
        map.put("quote", QUOTE.toMap());
        Runtime runtime = Runtime.getRuntime();
        map.put("heapUsedMb", (runtime.totalMemory() - runtime.freeMemory()) / 1048576.0);
        map.put("platformThreads", Thread.getAllStackTraces().size());
        map.put("libraryThreads", Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("resilience4cata")).count());
        map.put("snapshot", snapshot);
        return Json.write(map);
    }
}
