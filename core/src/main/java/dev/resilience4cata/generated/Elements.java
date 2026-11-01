package dev.resilience4cata.generated;

import java.util.concurrent.CompletableFuture;

/**
 * One call of a streaming method (a method that returns a {@code Flux}), as the policies that need its
 * elements see it. The reactor module implements it; generated code passes it to the
 * {@code executeStreamAsync(call, elements)} methods of {@link TimeoutPolicy}, {@link CircuitBreakerPolicy}
 * and {@link AdaptiveLimitPolicy}.
 *
 * <p>Attempts of a call run one after another. A policy registers a listener before it calls the next
 * layer; the attempt that this layer starts (the next subscription to the delegate's stream) takes every
 * listener registered since the previous attempt started, so a listener only ever hears about the
 * elements of its own attempt.
 */
public interface Elements {

    /**
     * Registers a listener for the attempt that starts next.
     *
     * @param listener the policy's state for this attempt
     * @param everyElement {@code true} to hear about every element; {@code false} for the first one only
     */
    void listen(ElementListener listener, boolean everyElement);

    /**
     * The stage of an attempt whose stream ended while it was subscribed: completed with {@code null}, shared and
     * immutable. Policies return it as it is where they would return a completed copy of a done stage.
     *
     * @return the shared stage
     */
    @dev.resilience4cata.annotation.Internal
    static CompletableFuture<Void> ended() {
        return Ended.INSTANCE;
    }
}
