package dev.resilience4cata.generated;

/**
 * A policy's per-call state that wants to hear about the elements of a streaming call (a method that
 * returns a {@code Flux}): the timeout that restarts on every element, and the circuit breaker and
 * adaptive limit that time a stream until its first element. Registered with {@link Elements#listen}.
 */
@FunctionalInterface
public interface ElementListener {

    /**
     * The attempt this listener was registered for emitted an element. Called on the thread that emits it,
     * one element at a time; it must be quick and must not throw.
     */
    void element();
}
