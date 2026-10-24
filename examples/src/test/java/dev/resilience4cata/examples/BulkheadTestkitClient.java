package dev.resilience4cata.examples;

import dev.resilience4cata.annotation.Bulkhead;

/** A bulkhead whose callers wait up to 30 s for a slot, tested on a {@code ManualClock} without waiting. */
public interface BulkheadTestkitClient {

    @Bulkhead(name = "renderer", maxConcurrent = 1, maxWait = "30s")
    String render(String id);
}
