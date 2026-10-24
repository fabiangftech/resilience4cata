package dev.resilience4cata.generated;

import dev.resilience4cata.RetryConfig;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

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

    /**
     * Runs an asynchronous call with retries. A failed attempt is retried after the backoff delay
     * without blocking any thread: the next attempt starts on a new virtual thread.
     *
     * @param call one attempt: the next layer
     * @param <T> the result type
     * @return a future that completes with the first successful attempt, or the last failure
     */
    public <T> CompletableFuture<T> executeAsync(AsyncCall<T> call) {
        return executeAsync(call, null, null);
    }

    /**
     * Same as {@link #executeAsync(AsyncCall)}, and when the retries give up, runs the fallback with the
     * number of attempts made.
     *
     * @param call one attempt: the next layer
     * @param fallback decides which failures get the fallback; {@code null} for none
     * @param recovery the generated fallback; {@code null} for none
     * @param <T> the result type
     * @return a future that completes with the first successful attempt, the fallback's result, or the last failure
     */
    public <T> CompletableFuture<T> executeAsync(AsyncCall<T> call,
            @Nullable FallbackPolicy fallback, @Nullable Recovery<T> recovery) {
        CompletableFuture<T> result = new CompletableFuture<>();
        new AsyncAttempts<>(this, call, fallback, recovery, result, start()).run();
        return result;
    }

    /** The state of one asynchronous call across its attempts. Each attempt completes before the next starts. */
    private static final class AsyncAttempts<T> implements Runnable, BiConsumer<T, Throwable> {
        private final RetryPolicy policy;
        private final AsyncCall<T> call;
        private final @Nullable FallbackPolicy fallback;
        private final @Nullable Recovery<T> recovery;
        private final CompletableFuture<T> result;
        private final long start;
        private int attempts; // written by one attempt's callback before the next attempt is started

        AsyncAttempts(RetryPolicy policy, AsyncCall<T> call, @Nullable FallbackPolicy fallback,
                @Nullable Recovery<T> recovery, CompletableFuture<T> result, long start) {
            this.policy = policy;
            this.call = call;
            this.fallback = fallback;
            this.recovery = recovery;
            this.result = result;
            this.start = start;
        }

        @Override
        public void run() {
            AsyncSupport.call(call).whenComplete(this);
        }

        @Override
        public void accept(T value, Throwable failure) {
            if (failure == null) {
                result.complete(value);
                return;
            }
            Throwable cause = AsyncSupport.unwrap(failure);
            long delay = policy.onFailure(++attempts, cause, start);
            if (delay >= 0) {
                AsyncSupport.later(delay, this);
            } else if (fallback != null && recovery != null) {
                fallback.recoverOrFail(cause, attempts, recovery, result);
            } else {
                result.completeExceptionally(cause);
            }
        }
    }
}
