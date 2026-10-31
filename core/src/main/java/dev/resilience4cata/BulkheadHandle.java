package dev.resilience4cata;

/**
 * Inspects one bulkhead. Obtained from {@link ResilienceRegistry#bulkhead(String)}. Implemented by the library
 * only, not meant to be implemented or mocked: methods may be added in minor versions.
 */
public interface BulkheadHandle {

    /**
     * The bulkhead's id: its {@code name}, or the derived
     * {@code <type>#<method>(<parameter types>)}: the qualified name of the annotated interface or class (nested types joined by {@code .}), then the method name and its erased parameter types, fully qualified and separated by commas without spaces, for example {@code com.acme.PaymentClient#charge(java.lang.String,int)}.
     */
    String id();

    /** The configuration the bulkhead was created from. */
    BulkheadConfig config();

    /** Number of calls running inside the bulkhead right now. */
    int concurrentCalls();

    /**
     * Number of calls waiting for a slot right now: asynchronous calls queued for a released slot
     * plus synchronous threads blocked on the bulkhead. Always 0 when {@code maxWait} is 0, since
     * a full bulkhead then rejects at once. The count is a snapshot meant for monitoring; it may be
     * stale by the time it is read.
     */
    int waitingCalls();

    /** Reads the current counts into a record. */
    BulkheadSnapshot snapshot();
}
