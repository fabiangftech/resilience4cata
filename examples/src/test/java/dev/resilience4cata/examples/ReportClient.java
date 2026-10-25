package dev.resilience4cata.examples;

import dev.resilience4cata.annotation.Bulkhead;
import dev.resilience4cata.annotation.Timeout;

/** Bulkheads alone, shared by name, and combined with a timeout. */
public interface ReportClient {

    @Bulkhead(maxConcurrent = 2)
    String generate(String id);

    @Bulkhead(name = "exports", maxConcurrent = 1)
    String exportCsv(String id);

    @Bulkhead(name = "exports", maxConcurrent = 1)
    String exportPdf(String id);

    /** Holds the slot that {@link #waitForSlot} waits for; no timeout, so it keeps it until released. */
    @Bulkhead(name = "slots", maxConcurrent = 1, maxWait = "10s")
    String holdSlot(String id);

    @Timeout("100ms")
    @Bulkhead(name = "slots", maxConcurrent = 1, maxWait = "10s")
    String waitForSlot(String id);
}
