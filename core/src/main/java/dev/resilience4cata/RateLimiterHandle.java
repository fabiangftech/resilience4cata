package dev.resilience4cata;

/**
 * Inspects one rate limiter. Obtained from {@link ResilienceRegistry#rateLimiter(String)}. Implemented by the
 * library only, not meant to be implemented or mocked: methods may be added in minor versions.
 */
public interface RateLimiterHandle {

    /**
     * The rate limiter's id: its {@code name}, or the derived
     * {@code <type>#<method>(<parameter types>)}: the qualified name of the annotated interface or class (nested types joined by {@code .}), then the method name and its erased parameter types, fully qualified and separated by commas without spaces, for example {@code com.acme.PaymentClient#charge(java.lang.String,int)}.
     */
    String id();

    /** The configuration the rate limiter was created from. */
    RateLimiterConfig config();

    /**
     * Number of calls that would be let through right now without waiting, between 0 and
     * {@code permits}. A snapshot: concurrent calls change it immediately.
     */
    int availablePermits();

    /** Reads the current state into a record. */
    RateLimiterSnapshot snapshot();
}
