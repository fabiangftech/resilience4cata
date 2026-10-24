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

    @Timeout("100ms")
    @Bulkhead(maxConcurrent = 1, maxWait = "10s")
    String waitForSlot(String id);
}
