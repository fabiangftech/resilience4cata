package dev.resilience4cata.event;

/** Events of a fallback. */
public sealed interface FallbackEvent extends ResilienceEvent {

    /**
     * A fallback runs instead of rethrowing a failure: the failure matched the fallback's
     * {@code applyOn}/{@code skipOn} rules and, with sibling fallback methods, the exception parameter
     * of one of them accepts it. Published once per call, before the fallback runs, so also when the
     * fallback itself throws. A failure that is rethrown (because of {@code skipOn}, or because no
     * sibling method accepts its type) publishes nothing.
     *
     * @param instance the method's id, {@code <interface>#<method>(<parameter types>)}
     * @param nanoTime when the fallback was chosen
     * @param failure the failure being handled
     */
    record Applied(String instance, long nanoTime, Throwable failure) implements FallbackEvent {
    }
}
