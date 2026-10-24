package dev.resilience4cata.event;

/** Events of a fallback. */
public sealed interface FallbackEvent extends ResilienceEvent {

    /**
     * A failure matched the fallback's {@code applyOn}/{@code skipOn} rules and the fallback runs
     * instead of rethrowing it. Published before the fallback runs, so also when the fallback itself
     * throws. With sibling fallback methods, a failure that none of their exception parameters
     * accepts is still rethrown.
     *
     * @param instance the method's id, {@code <interface>#<method>(<parameter types>)}
     * @param nanoTime when the fallback was chosen
     * @param failure the failure being handled
     */
    record Applied(String instance, long nanoTime, Throwable failure) implements FallbackEvent {
    }
}
