package dev.resilience4cata;

/** Inspects one bulkhead. Obtained from {@link ResilienceRegistry#bulkhead(String)}. */
public interface BulkheadHandle {

    /** The bulkhead's id: its {@code name}, or the derived {@code <interface>#<method>(<parameters>)}. */
    String id();

    /** The configuration the bulkhead was created from. */
    BulkheadConfig config();

    /** Number of calls running inside the bulkhead right now. */
    int concurrentCalls();
}
