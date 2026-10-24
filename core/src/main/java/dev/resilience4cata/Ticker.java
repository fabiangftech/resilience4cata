package dev.resilience4cata;

import java.util.concurrent.Future;
import java.util.function.LongConsumer;

/**
 * A source of time that replaces the system clock in a {@link ResilienceRegistry}, so that tests can
 * control time instead of waiting for it. Set it with {@link ResilienceRegistry.Builder#ticker(Ticker)};
 * the {@code resilience4cata-testkit} module provides {@code ManualClock}, which is what most tests
 * should use. Without a ticker, the registry uses {@link System#nanoTime()}, {@link Thread#sleep} and the
 * library's own timer threads, and none of this interface is called.
 *
 * <p>With a ticker, every policy the registry creates reads {@link #read()} instead of the system clock
 * (circuit breaker waits and slow calls, rate limiter permits, retry {@code maxDuration}), sleeps through
 * {@link #sleep(long)} (retry backoff, rate limiter waits), schedules asynchronous delays (async retry
 * backoff, async rate limiter waits, async bulkhead wait limits, async timeouts) through
 * {@link #schedule(long, Runnable)}, and times out synchronous calls when the ticker reports, through
 * {@link #onAdvance(LongConsumer)}, that it has moved past their deadline.
 *
 * <p>Implementations must be thread-safe: policies call them from every thread that makes calls.
 */
public interface Ticker {

    /**
     * The current time in nanoseconds. Only differences between two readings are meaningful, as for
     * {@link System#nanoTime()}; it must never go backwards.
     *
     * @return the current time
     */
    long read();

    /**
     * Waits for {@code nanos}, as {@link Thread#sleep} would. A manual ticker advances itself instead of
     * blocking.
     *
     * @param nanos how long to wait; positive
     * @throws InterruptedException if the thread was interrupted, before or while waiting
     */
    void sleep(long nanos) throws InterruptedException;

    /**
     * Runs {@code task} once, when this ticker has moved {@code delayNanos} past the current time.
     *
     * @param delayNanos the delay; positive
     * @param task what to run; it must not block for long
     * @return a future whose {@link Future#cancel(boolean) cancel} stops the task from running
     */
    Future<?> schedule(long delayNanos, Runnable task);

    /**
     * Registers a listener called with the new time every time this ticker moves forward. The registry
     * registers one listener when it is built, to time out synchronous calls. A ticker that follows real
     * time must call it regularly, at least every millisecond, or synchronous timeouts fire late.
     *
     * @param listener called with the time {@link #read()} now returns; it never blocks
     */
    void onAdvance(LongConsumer listener);
}
