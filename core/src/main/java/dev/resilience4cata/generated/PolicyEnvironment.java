package dev.resilience4cata.generated;

import dev.resilience4cata.Ticker;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;
import org.jspecify.annotations.Nullable;

/**
 * What the policies of one registry share besides their configuration: the clock, the sleeper, the
 * random generator for jitter, and the timers. Built once per {@link dev.resilience4cata.ResilienceRegistry}
 * and immutable; policies copy what they need into {@code final} fields when they are created.
 *
 * <p>{@link #DEFAULT} is the production environment: {@link System#nanoTime()}, {@link Thread#sleep},
 * {@link java.util.concurrent.ThreadLocalRandom}, the shared timeout watchdog and the shared timers. Its
 * clock and sleeper are one lambda each for the whole library, so the call sites that read them stay
 * monomorphic in production.
 *
 * <p>An environment made {@link #of(Ticker, RandomGenerator) of} a {@link Ticker} reads, sleeps and
 * schedules through that ticker, and has its own watchdog driven by the ticker's advances: synchronous
 * timeouts fire as soon as the ticker reports a time past their deadline, with no slack.
 */
public final class PolicyEnvironment {

    private static final LongSupplier SYSTEM_TICKER = System::nanoTime;
    private static final RetryPolicy.Sleeper SYSTEM_SLEEPER = TimeUnit.NANOSECONDS::sleep;

    /** The production environment. */
    public static final PolicyEnvironment DEFAULT = new PolicyEnvironment(SYSTEM_TICKER, SYSTEM_SLEEPER, null, null, null);

    final LongSupplier ticker;
    final RetryPolicy.Sleeper sleeper;
    /** {@code null}: {@code ThreadLocalRandom}. */
    final @Nullable RandomGenerator random;
    /** {@code null}: real time, the shared watchdog and the shared timers. */
    final @Nullable Ticker manual;
    /** {@code null}: {@link Watchdog#shared()}, started on first use. */
    private final @Nullable Watchdog watchdog;
    /** The registry's event listeners; policies resolve their {@link EventSink}s from it once, in their constructor. */
    final EventDispatcher events;

    private PolicyEnvironment(LongSupplier ticker, RetryPolicy.Sleeper sleeper, @Nullable RandomGenerator random,
            @Nullable Ticker manual, @Nullable Watchdog watchdog) {
        this.ticker = ticker;
        this.sleeper = sleeper;
        this.random = random;
        this.manual = manual;
        this.watchdog = watchdog;
        this.events = EventDispatcher.NONE;
    }

    private PolicyEnvironment(PolicyEnvironment base, EventDispatcher events) {
        this.ticker = base.ticker;
        this.sleeper = base.sleeper;
        this.random = base.random;
        this.manual = base.manual;
        this.watchdog = base.watchdog;
        this.events = events;
    }

    /**
     * Returns a copy of this environment with these event listeners.
     *
     * @param events the listeners
     * @return the environment; this one if nothing changes
     */
    public PolicyEnvironment withEvents(EventDispatcher events) {
        return events == this.events ? this : new PolicyEnvironment(this, events);
    }

    /** Package-private, for core tests: a fixed clock and sleeper, the shared timers, and these listeners. */
    static PolicyEnvironment forTest(LongSupplier ticker, RetryPolicy.Sleeper sleeper, EventDispatcher events) {
        return forTest(ticker, sleeper).withEvents(events);
    }

    /**
     * Returns an environment that uses {@code ticker} for time and {@code random} for jitter.
     *
     * @param ticker the time source; {@code null} for the system clock and the library's timers
     * @param random the generator for retry jitter; {@code null} for {@code ThreadLocalRandom}. Called
     *     from the threads whose calls fail, so it must be thread-safe if several threads can fail at once
     *     ({@link java.util.Random} is; {@link java.util.SplittableRandom} is not)
     * @return the environment
     */
    public static PolicyEnvironment of(@Nullable Ticker ticker, @Nullable RandomGenerator random) {
        if (ticker == null) {
            return random == null ? DEFAULT : new PolicyEnvironment(SYSTEM_TICKER, SYSTEM_SLEEPER, random, null, null);
        }
        Watchdog dog = Watchdog.manual(ticker.read());
        ticker.onAdvance(dog::tick);
        return new PolicyEnvironment(ticker::read, ticker::sleep, random, ticker, dog);
    }

    /** Package-private, for core tests: a fixed clock and sleeper, with the shared timers. */
    static PolicyEnvironment forTest(LongSupplier ticker, RetryPolicy.Sleeper sleeper) {
        return new PolicyEnvironment(ticker, sleeper, null, null, null);
    }

    /** The watchdog for synchronous timeouts. */
    Watchdog watchdog() {
        Watchdog dog = watchdog;
        return dog != null ? dog : Watchdog.shared();
    }

    /** The timer for asynchronous timeouts, or {@code null} when they are scheduled on the {@link #manual} ticker. */
    @Nullable TimeoutTimer timeoutTimer() {
        return manual == null ? TimeoutTimer.shared() : null;
    }

    /**
     * Runs {@code task} after {@code delayNanos}. Without a ticker: at once on a new virtual thread if the
     * delay is not positive, otherwise on a new virtual thread from the shared timer. With a manual ticker,
     * always on the ticker, even with no delay: on the thread that next advances the ticker (with no delay,
     * by any amount, {@code advance(Duration.ZERO)} included), so tests stay deterministic.
     */
    @SuppressWarnings("java:S1452") // the JDK's own type for a scheduled Runnable; callers only cancel it
    @Nullable Future<?> later(long delayNanos, Runnable task) {
        Ticker time = manual;
        if (time == null) {
            return AsyncSupport.later(delayNanos, task);
        }
        return time.schedule(Math.max(delayNanos, 0), task);
    }
}
