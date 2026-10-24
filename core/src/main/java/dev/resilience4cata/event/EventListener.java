package dev.resilience4cata.event;

/**
 * Receives events of one type. Called synchronously on the thread that caused the event, so it must
 * be fast and must not block; hand the event to a queue or an executor for slow work. An exception
 * it throws is reported to the thread's uncaught exception handler and does not affect the call.
 *
 * @param <E> the event type
 */
@FunctionalInterface
public interface EventListener<E extends ResilienceEvent> {

    /**
     * Handles one event.
     *
     * @param event the event
     */
    void onEvent(E event);
}
