package dev.resilience4cata;

/**
 * Inspects one adaptive limit. Obtained from {@link ResilienceRegistry#adaptiveLimit(String)}. Implemented by the
 * library only, not meant to be implemented or mocked: methods may be added in minor versions.
 */
public interface AdaptiveLimitHandle {

    /**
     * The adaptive limit's id: its {@code name}, or the derived
     * {@code <type>#<method>(<parameter types>)}: the qualified name of the annotated interface or class (nested types joined by {@code .}), then the method name and its erased parameter types, fully qualified and separated by commas without spaces, for example {@code com.acme.PaymentClient#charge(java.lang.String,int)}.
     */
    String id();

    /** The configuration the adaptive limit was created from. */
    AdaptiveLimitConfig config();

    /**
     * The current limit: how many calls may run at the same time now, between {@code minLimit} and {@code
     * maxLimit}.
     */
    int limit();

    /** Number of calls running inside the adaptive limit right now. */
    int concurrentCalls();

    /** Reads the current values into a record. */
    AdaptiveLimitSnapshot snapshot();
}
