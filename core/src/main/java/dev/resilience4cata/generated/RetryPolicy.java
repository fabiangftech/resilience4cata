package dev.resilience4cata.generated;

import dev.resilience4cata.RetryConfig;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Retry decisions for generated code. Allocation-free on the success path: generated code keeps the
 * attempt count in a local variable and calls this class only to start and after a failure.
 *
 * <pre>{@code
 * long start = retry.start();
 * int attempts = 0;
 * while (true) {
 *     try {
 *         return delegate.call();
 *     } catch (Throwable e) {
 *         long delay = retry.onFailure(++attempts, e, start);
 *         if (delay < 0 || !retry.pause(delay)) throw e;
 *     }
 * }
 * }</pre>
 */
public final class RetryPolicy {

    /** Returned by {@link #onFailure} when the call must not be retried. */
    public static final long NO_RETRY = -1L;

    private final RetryConfig config;
    private final boolean enabled;
    private final int maxAttempts;
    private final long delayNanos;
    private final boolean exponential;
    private final double multiplier;
    private final long maxDelayNanos;
    private final RetryConfig.Jitter jitter;
    private final long maxDurationNanos;
    private final ClassValue<Boolean> retryable;
    private final LongSupplier ticker;
    private final Sleeper sleeper;

    /**
     * Creates a policy from a resolved configuration.
     *
     * @param config the configuration
     */
    public RetryPolicy(RetryConfig config) {
        this(config, System::nanoTime, TimeUnit.NANOSECONDS::sleep);
    }

    RetryPolicy(RetryConfig config, LongSupplier ticker, Sleeper sleeper) {
        this.config = config;
        this.enabled = config.enabled();
        this.maxAttempts = config.maxAttempts();
        this.delayNanos = config.delay().toNanos();
        this.exponential = config.backoff() == RetryConfig.Backoff.EXPONENTIAL;
        this.multiplier = config.multiplier();
        this.maxDelayNanos = config.maxDelay().toNanos();
        this.jitter = config.jitter();
        this.maxDurationNanos = config.maxDuration().toNanos();
        this.retryable = new Classification(config.retryOn(), config.abortOn());
        this.ticker = ticker;
        this.sleeper = sleeper;
    }

    /** Returns the configuration this policy was created from. */
    public RetryConfig config() {
        return config;
    }

    /**
     * Called once before the first attempt.
     *
     * @return a start timestamp to pass to {@link #onFailure}; reads the clock only if {@code maxDuration} is set
     */
    public long start() {
        return maxDurationNanos > 0 ? ticker.getAsLong() : 0L;
    }

    /**
     * Decides whether to retry after a failure.
     *
     * @param attempts attempts made so far, including the one that just failed
     * @param failure the exception thrown by the attempt
     * @param start the value returned by {@link #start()}
     * @return the delay in nanoseconds before the next attempt, or {@link #NO_RETRY}
     */
    public long onFailure(int attempts, Throwable failure, long start) {
        if (!enabled || attempts >= maxAttempts || failure instanceof InterruptedException
                || Thread.currentThread().isInterrupted() || !retryable.get(failure.getClass())) {
            return NO_RETRY;
        }
        long delay = withJitter(baseDelay(attempts));
        if (maxDurationNanos > 0 && ticker.getAsLong() - start + delay > maxDurationNanos) {
            return NO_RETRY;
        }
        return delay;
    }

    /**
     * Waits before the next attempt.
     *
     * @param delayNanos the value returned by {@link #onFailure}
     * @return {@code false} if the thread was interrupted; the interrupt flag is then set again and the
     *     caller must stop retrying
     */
    public boolean pause(long delayNanos) {
        if (delayNanos <= 0) {
            return true;
        }
        try {
            sleeper.sleep(delayNanos);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    long baseDelay(int attempts) {
        if (!exponential) {
            return delayNanos;
        }
        double delay = delayNanos * Math.pow(multiplier, attempts - 1);
        return delay >= maxDelayNanos ? maxDelayNanos : (long) delay;
    }

    private long withJitter(long delay) {
        if (delay <= 0) {
            return 0L;
        }
        return switch (jitter) {
            case NONE -> delay;
            case FULL -> ThreadLocalRandom.current().nextLong(delay + 1);
            case EQUAL -> delay / 2 + ThreadLocalRandom.current().nextLong(delay - delay / 2 + 1);
        };
    }

    /** Sleeps for a number of nanoseconds. Replaced in tests. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long nanos) throws InterruptedException;
    }

    /** Caches, per exception class, whether it is retryable. {@code abortOn} wins over {@code retryOn}. */
    private static final class Classification extends ClassValue<Boolean> {
        private final List<Class<? extends Throwable>> retryOn;
        private final List<Class<? extends Throwable>> abortOn;

        Classification(List<Class<? extends Throwable>> retryOn, List<Class<? extends Throwable>> abortOn) {
            this.retryOn = retryOn;
            this.abortOn = abortOn;
        }

        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<? extends Throwable> abort : abortOn) {
                if (abort.isAssignableFrom(type)) {
                    return false;
                }
            }
            for (Class<? extends Throwable> retry : retryOn) {
                if (retry.isAssignableFrom(type)) {
                    return true;
                }
            }
            return false;
        }
    }
}
