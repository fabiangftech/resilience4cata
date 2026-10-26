/**
 * Typed events published by the resilience policies: circuit breaker transitions and rejections,
 * retries, timeouts, bulkhead, rate limiter and adaptive limit rejections, adaptive limit changes, and
 * fallbacks.
 *
 * <p>Listeners are registered on {@code ResilienceRegistry.builder()} with
 * {@code listen(EventType.class, listener)} or {@code listen(instance, EventType.class, listener)}.
 * Each policy resolves its listeners once, when it is created: a policy with no listener for an
 * event type never allocates that event, takes no lock and does no extra write. Listeners run
 * synchronously, on the thread that caused the event (the caller's thread for synchronous calls,
 * a library virtual thread for asynchronous timeouts and deferred retries), so they must be fast
 * and non-blocking. A listener that throws does not break the call: the exception goes to the
 * thread's uncaught exception handler and the remaining listeners still run.
 *
 * <p>Nothing in this package is null unless marked {@code @Nullable}.
 */
@NullMarked
package dev.resilience4cata.event;

import org.jspecify.annotations.NullMarked;
