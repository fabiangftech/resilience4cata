package dev.resilience4cata.event;

import java.time.Duration;

/** Events of a timeout. */
public sealed interface TimeoutEvent extends ResilienceEvent {

    /**
     * A call ran past its timeout. For a synchronous call it is published on the caller's thread just
     * before {@code CallTimeoutException} is thrown; for an asynchronous call, on a library virtual
     * thread when the returned future fails.
     *
     * @param instance the timeout's id
     * @param nanoTime when the timeout was detected
     * @param timeout the configured timeout
     */
    record TimedOut(String instance, long nanoTime, Duration timeout) implements TimeoutEvent {
    }
}
