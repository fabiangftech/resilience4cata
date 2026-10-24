package dev.resilience4cata.generated;

import dev.resilience4cata.RateLimitExceededException;
import dev.resilience4cata.RateLimiterConfig;
import dev.resilience4cata.RateLimiterHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;

/**
 * Rate limiter for generated code: lets through {@code permits} calls per {@code period}.
 *
 * <pre>{@code
 * if (!rateLimiter.tryAcquire()) throw rateLimiter.rejection();
 * return delegate.call();
 * }</pre>
 *
 * <p>The algorithm is GCRA (generic cell rate algorithm), the meter form of a token bucket: the whole
 * state is one {@code long}, the theoretical arrival time ({@code tat}) of the next call if calls came
 * exactly at the configured rate. Each permit moves {@code tat} forward by {@code interval = period /
 * permits}; a call is let through if {@code tat} would stay within {@code permits × interval} of now,
 * which is what allows a burst of {@code permits} calls after a quiet period. Nothing is allocated and
 * no background thread refills permits.
 *
 * <p>Admitting a call is one clock read and one compare-and-set. A rejection only reads, so an
 * overloaded limiter does not add contention on its own cache line. A caller that loses the
 * compare-and-set to another admitted call parks for the shortest possible time before trying again:
 * this only happens when admitted calls arrive faster than the cache line can move between cores,
 * and it keeps throughput from collapsing then, at the cost of a longer wait for that caller (on Linux,
 * a platform thread's shortest park is about 50 µs of timer slack). With {@code maxWait > 0}, a call
 * whose permit comes within {@code maxWait} reserves it with the same compare-and-set, then sleeps
 * until it is due: waiters know their slot, so there is no wake-up stampede.
 */
public final class RateLimiterPolicy implements RateLimiterHandle {

    private final String id;
    private final RateLimiterConfig config;
    private final boolean enabled;
    private final long interval;
    private final long tolerance;
    private final long maxWaitNanos;
    private final LongSupplier ticker;
    private final RetryPolicy.Sleeper sleeper;
    private final PaddedTat tat = new PaddedTat();
    private final RateLimitExceededException rejection;

    /**
     * Creates a rate limiter.
     *
     * @param id the rate limiter's id, used in the rejection message
     * @param config the configuration
     */
    public RateLimiterPolicy(String id, RateLimiterConfig config) {
        this(id, config, System::nanoTime, TimeUnit.NANOSECONDS::sleep);
    }

    RateLimiterPolicy(String id, RateLimiterConfig config, LongSupplier ticker, RetryPolicy.Sleeper sleeper) {
        this.id = id;
        this.config = config;
        this.enabled = config.enabled();
        // Rounded down: the rate is at most 1 ns per interval too fast, and the burst is exactly `permits`.
        this.interval = config.period().toNanos() / config.permits();
        this.tolerance = interval * config.permits();
        this.maxWaitNanos = config.maxWait().toNanos();
        this.ticker = ticker;
        this.sleeper = sleeper;
        this.rejection = new RateLimitExceededException(id);
        tat.set(ticker.getAsLong()); // a full burst is available from the start
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public RateLimiterConfig config() {
        return config;
    }

    @Override
    public int availablePermits() {
        if (!enabled) {
            return config.permits();
        }
        long now = ticker.getAsLong();
        long backlog = Math.max(tat.get() - now, 0);
        return (int) Math.max((tolerance - backlog) / interval, 0);
    }

    /** Returns the preallocated exception to throw when {@link #tryAcquire()} returns {@code false}. */
    public RateLimitExceededException rejection() {
        return rejection;
    }

    /**
     * Takes a permit, waiting up to {@code maxWait} for it.
     *
     * @return {@code true} if the call may run. {@code false} if the next permit is further away than
     *     {@code maxWait}, or the thread was interrupted while waiting for it (the interrupt flag is set
     *     again and the permit is given back).
     */
    public boolean tryAcquire() {
        if (!enabled) {
            return true;
        }
        long now = ticker.getAsLong();
        PaddedTat cell = tat;
        long current = cell.getOpaque();
        while (true) {
            // Compared by subtraction: nanoTime values may be negative and are only meaningful as differences.
            long next = (current - now > 0 ? current : now) + interval;
            long wait = next - now - tolerance;
            if (wait > maxWaitNanos) {
                return false; // nothing was written
            }
            if (cell.weakCompareAndSet(current, next)) {
                return wait <= 0 || pause(wait);
            }
            // Lost the race to another admitted call. Parking briefly takes this thread out of the fight
            // for the cache line: with four threads it cut the cost per call from 660 to 130 ns, where
            // spinning or yielding did not help (see docs/en/benchmarks.md).
            LockSupport.parkNanos(1);
            current = cell.getOpaque();
        }
    }

    private boolean pause(long nanos) {
        try {
            sleeper.sleep(nanos);
            return true;
        } catch (InterruptedException e) {
            tat.add(-interval); // give the reserved permit back
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Padding before {@code tat}, so that it does not share a cache line with what precedes it in memory. */
    @SuppressWarnings("unused")
    private static class PadBefore {
        long p0, p1, p2, p3, p4, p5, p6, p7;
    }

    private static class Value extends PadBefore {
        static final VarHandle VALUE;

        static {
            try {
                VALUE = MethodHandles.lookup().findVarHandle(Value.class, "value", long.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        @SuppressWarnings("unused") // accessed through VALUE
        volatile long value;
    }

    /**
     * Superclass fields are laid out first, so {@code tat} sits between two blocks of padding.
     *
     * <p>{@code tat} guards no other memory: the only requirement is that no update is lost, which any
     * compare-and-set guarantees. So the plain (unordered) variants are used, which are the cheapest on
     * ARM.
     */
    @SuppressWarnings("unused")
    private static final class PaddedTat extends Value {
        long q0, q1, q2, q3, q4, q5, q6, q7;

        long getOpaque() {
            return (long) VALUE.getOpaque(this);
        }

        boolean weakCompareAndSet(long expected, long next) {
            return VALUE.weakCompareAndSetPlain(this, expected, next);
        }

        void add(long delta) {
            VALUE.getAndAdd(this, delta);
        }

        long get() {
            return (long) VALUE.getVolatile(this);
        }

        void set(long value) {
            VALUE.setVolatile(this, value);
        }
    }

    /**
     * Runs an asynchronous call when a permit is available. With {@code maxWait > 0}, a call whose
     * permit comes within {@code maxWait} reserves it and starts when it is due, without blocking any
     * thread. A rejection is a failed future.
     *
     * @param call the next layer
     * @param <T> the result type
     * @return a future that completes with the call's outcome
     */
    public <T> CompletableFuture<T> executeAsync(AsyncCall<T> call) {
        long wait = reserve();
        if (wait < 0) {
            return CompletableFuture.failedFuture(rejection);
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        if (wait == 0) {
            AsyncSupport.relay(AsyncSupport.call(call), result);
        } else {
            AsyncSupport.later(wait, () -> AsyncSupport.relay(AsyncSupport.call(call), result));
        }
        return result;
    }

    /**
     * Takes or reserves a permit without waiting for it.
     *
     * @return {@code 0} if the permit is available now, the nanoseconds until the reserved permit is due
     *     (at most {@code maxWait}), or {@code -1} if the call must be rejected (nothing is reserved then)
     */
    long reserve() {
        if (!enabled) {
            return 0;
        }
        long now = ticker.getAsLong();
        PaddedTat cell = tat;
        long current = cell.getOpaque();
        while (true) {
            long next = (current - now > 0 ? current : now) + interval;
            long wait = next - now - tolerance;
            if (wait > maxWaitNanos) {
                return -1;
            }
            if (cell.weakCompareAndSet(current, next)) {
                return Math.max(wait, 0);
            }
            LockSupport.parkNanos(1);
            current = cell.getOpaque();
        }
    }
}
