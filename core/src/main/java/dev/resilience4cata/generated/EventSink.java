package dev.resilience4cata.generated;

import dev.resilience4cata.event.EventListener;
import dev.resilience4cata.event.ResilienceEvent;
import java.util.List;

/**
 * The listeners of one event type on one policy instance. Policies hold one in a {@code final} field,
 * {@code null} when nobody listens, and build the event only after checking it:
 *
 * <pre>{@code
 * EventSink<Rejected> sink = rejectedSink;
 * if (sink != null) sink.publish(new Rejected(id, ticker.getAsLong()));
 * }</pre>
 *
 * @param <E> the event type
 */
public final class EventSink<E extends ResilienceEvent> {

    private final EventListener<? super E>[] listeners;

    @SuppressWarnings("unchecked")
    EventSink(List<EventListener<? super E>> listeners) {
        this.listeners = (EventListener<? super E>[]) listeners.toArray(new EventListener<?>[0]);
    }

    /**
     * Calls every listener, in registration order, on the current thread. A listener that throws does
     * not stop the others and does not reach the caller: its exception is passed to the current thread's
     * uncaught exception handler. Only {@link VirtualMachineError}s propagate.
     *
     * @param event the event
     */
    public void publish(E event) {
        for (EventListener<? super E> listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (VirtualMachineError e) {
                throw e;
            } catch (Throwable e) {
                report(e);
            }
        }
    }

    private static void report(Throwable failure) {
        Thread thread = Thread.currentThread();
        try {
            thread.getUncaughtExceptionHandler().uncaughtException(thread, failure);
        } catch (Throwable ignored) {
            // The handler failed too: nothing more can be done without breaking the call.
        }
    }
}
