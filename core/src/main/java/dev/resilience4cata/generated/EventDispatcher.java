package dev.resilience4cata.generated;

import dev.resilience4cata.event.EventListener;
import dev.resilience4cata.event.ResilienceEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The event listeners of one registry, fixed when the registry is built. A policy asks it once, in
 * its constructor, for the {@link EventSink} of each event type it publishes, and keeps the result in
 * a {@code final} field: {@code null} when nobody listens, so publishing costs one null check and the
 * event object is never created.
 */
public final class EventDispatcher {

    /** No listeners at all. */
    public static final EventDispatcher NONE = new EventDispatcher(List.of());

    private final List<Subscription> subscriptions;

    private EventDispatcher(List<Subscription> subscriptions) {
        this.subscriptions = List.copyOf(subscriptions);
    }

    /**
     * Creates a dispatcher.
     *
     * @param subscriptions the listeners, in registration order
     * @return the dispatcher, or {@link #NONE} if there are none
     */
    public static EventDispatcher of(List<Subscription> subscriptions) {
        return subscriptions.isEmpty() ? NONE : new EventDispatcher(subscriptions);
    }

    /** True if no listener is registered. */
    public boolean isEmpty() {
        return subscriptions.isEmpty();
    }

    /**
     * Returns the listeners for one event type of one policy instance.
     *
     * @param type the event record class the policy publishes
     * @param id the policy instance's id
     * @param <E> the event type
     * @return the sink, or {@code null} if nobody listens to that type on that instance
     */
    @SuppressWarnings("unchecked") // a listener of a supertype of E accepts E
    public <E extends ResilienceEvent> @Nullable EventSink<E> sink(Class<E> type, String id) {
        List<EventListener<? super E>> matching = new ArrayList<>();
        for (Subscription subscription : subscriptions) {
            if (subscription.type().isAssignableFrom(type)
                    && (subscription.id() == null || subscription.id().equals(id))) {
                matching.add((EventListener<? super E>) subscription.listener());
            }
        }
        return matching.isEmpty() ? null : new EventSink<>(matching);
    }

    /**
     * One registered listener.
     *
     * @param type the event type it listens to: a record such as {@code CircuitBreakerEvent.StateTransition},
     *     a policy interface such as {@code RetryEvent}, or {@code ResilienceEvent} for everything
     * @param id the only instance it listens to, or {@code null} for every instance
     * @param listener the listener; it must accept {@code type}
     */
    public record Subscription(Class<? extends ResilienceEvent> type, @Nullable String id,
            EventListener<?> listener) {

        /** Checks that nothing is null except {@code id}. */
        public Subscription {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(listener, "listener");
        }
    }
}
