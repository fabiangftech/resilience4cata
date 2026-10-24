package dev.resilience4cata.generated;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.RetryConfig;
import dev.resilience4cata.event.RetryEvent;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;
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

    private final String id;
    private final RetryConfig config;
    private final boolean enabled;
    private final int maxAttempts;
    private final long delayNanos;
    private final boolean exponential;
    private final double multiplier;
    private final long maxDelayNanos;
    private final RetryConfig.Jitter jitter;
    private final long maxDurationNanos;
    // One ClassValue per policy, as before classifiers: every extra ClassValue on the same exception class
    // shares that class's small lookup cache, and collisions there made failure paths contend under load.
    private final Classification verdicts;
    private final LongSupplier ticker;
    private final Sleeper sleeper;
    private final @Nullable RandomGenerator random;
    private final PolicyEnvironment environment;
    private final @Nullable EventSink<RetryEvent.Retrying> retrying;
    private final @Nullable EventSink<RetryEvent.GaveUp> gaveUp;

    /**
     * Creates a policy from a resolved configuration, with the production environment.
     *
     * @param config the configuration
     */
    public RetryPolicy(RetryConfig config) {
        this("retry", config, PolicyEnvironment.DEFAULT);
    }

    RetryPolicy(RetryConfig config, LongSupplier ticker, Sleeper sleeper) {
        this("retry", config, PolicyEnvironment.forTest(ticker, sleeper));
    }

    /**
     * Creates a policy from a resolved configuration.
     *
     * @param id the policy's id
     * @param config the configuration
     * @param environment the clock, sleeper, random generator and timers to use
     */
    public RetryPolicy(String id, RetryConfig config, PolicyEnvironment environment) {
        this.id = id;
        this.config = config;
        this.enabled = config.enabled();
        this.maxAttempts = config.maxAttempts();
        this.delayNanos = config.delay().toNanos();
        this.exponential = config.backoff() == RetryConfig.Backoff.EXPONENTIAL;
        this.multiplier = config.multiplier();
        this.maxDelayNanos = config.maxDelay().toNanos();
        this.jitter = config.jitter();
        this.maxDurationNanos = config.maxDuration().toNanos();
        this.verdicts = new Classification(config.retryOn(), config.abortOn());
        this.ticker = environment.ticker;
        this.sleeper = environment.sleeper;
        this.random = environment.random;
        this.environment = environment;
        this.retrying = environment.events.sink(RetryEvent.Retrying.class, id);
        this.gaveUp = environment.events.sink(RetryEvent.GaveUp.class, id);
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
        return decide(attempts, failure, start, verdicts.get(failure.getClass()) == Verdict.RETRY);
    }

    /**
     * Decides whether to retry after a failure, for a method with a {@link Classifier}: the
     * classifier's verdict replaces {@code retryOn}, while {@code abortOn} still wins over it. Only a
     * {@link Classifier.Outcome#FAILURE FAILURE} verdict is retried.
     *
     * @param attempts attempts made so far, including the one that just failed
     * @param failure the exception thrown by the attempt
     * @param start the value returned by {@link #start()}
     * @param outcome what the method's classifier returned for {@code failure}
     * @return the delay in nanoseconds before the next attempt, or {@link #NO_RETRY}
     */
    public long onFailure(int attempts, Throwable failure, long start, Classifier.Outcome outcome) {
        // With a classifier only abortOn counts: its verdict replaces retryOn.
        return decide(attempts, failure, start,
                outcome == Classifier.Outcome.FAILURE && verdicts.get(failure.getClass()) != Verdict.ABORT);
    }

    /**
     * Decides whether to retry after an attempt returned a result that the method's classifier
     * classified as {@link Classifier.Outcome#FAILURE FAILURE}. When this returns {@link #NO_RETRY},
     * the caller returns that last result.
     *
     * @param attempts attempts made so far, including the one whose result was rejected
     * @param start the value returned by {@link #start()}
     * @return the delay in nanoseconds before the next attempt, or {@link #NO_RETRY}
     */
    public long onFailedResult(int attempts, long start) {
        return decide(attempts, null, start, true);
    }

    /**
     * The one place where every retry decision is made. {@code failure} is null for a rejected result;
     * {@code retryable} is what the exception lists or the classifier said.
     */
    private long decide(int attempts, @Nullable Throwable failure, long start, boolean retryable) {
        if (!enabled) {
            return NO_RETRY;
        }
        // Same order of checks as without events; the reason is only worked out when someone listens.
        if (attempts >= maxAttempts || failure instanceof InterruptedException
                || Thread.currentThread().isInterrupted() || !retryable) {
            if (gaveUp != null) {
                publishGaveUp(attempts, failure, retryable);
            }
            return NO_RETRY;
        }
        long delay = withJitter(baseDelay(attempts));
        if (maxDurationNanos > 0 && ticker.getAsLong() - start + delay > maxDurationNanos) {
            EventSink<RetryEvent.GaveUp> sink = gaveUp;
            if (sink != null) {
                sink.publish(new RetryEvent.GaveUp(id, ticker.getAsLong(), attempts, RetryEvent.Reason.MAX_DURATION, failure));
            }
            return NO_RETRY;
        }
        EventSink<RetryEvent.Retrying> sink = retrying;
        if (sink != null) {
            sink.publish(new RetryEvent.Retrying(id, ticker.getAsLong(), attempts, Duration.ofNanos(delay), failure));
        }
        return delay;
    }

    private void publishGaveUp(int attempts, @Nullable Throwable failure, boolean retryable) {
        RetryEvent.Reason reason = attempts >= maxAttempts ? RetryEvent.Reason.MAX_ATTEMPTS
                : failure instanceof InterruptedException || Thread.currentThread().isInterrupted()
                        ? RetryEvent.Reason.INTERRUPTED
                        : RetryEvent.Reason.NOT_RETRYABLE;
        Objects.requireNonNull(gaveUp).publish(new RetryEvent.GaveUp(id, ticker.getAsLong(), attempts, reason, failure));
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
        if (jitter == RetryConfig.Jitter.NONE) {
            return delay;
        }
        RandomGenerator generator = random != null ? random : ThreadLocalRandom.current();
        return switch (jitter) {
            case NONE -> delay;
            case FULL -> generator.nextLong(delay + 1);
            case EQUAL -> delay / 2 + generator.nextLong(delay - delay / 2 + 1);
        };
    }

    /** Sleeps for a number of nanoseconds. Replaced in tests. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long nanos) throws InterruptedException;

        /** For policies that never sleep. */
        Sleeper NONE = nanos -> {
            throw new UnsupportedOperationException("this policy does not sleep");
        };
    }

    /** What the exception lists say about one exception class. */
    private enum Verdict {
        /** In {@code retryOn} and not in {@code abortOn}. */
        RETRY,
        /** In neither list. */
        NOT_LISTED,
        /** In {@code abortOn}, which wins over {@code retryOn}. */
        ABORT
    }

    /** Caches the {@link Verdict} per exception class. */
    private static final class Classification extends ClassValue<Verdict> {
        private final List<Class<? extends Throwable>> retryOn;
        private final List<Class<? extends Throwable>> abortOn;

        Classification(List<Class<? extends Throwable>> retryOn, List<Class<? extends Throwable>> abortOn) {
            this.retryOn = retryOn;
            this.abortOn = abortOn;
        }

        @Override
        protected Verdict computeValue(Class<?> type) {
            for (Class<? extends Throwable> abort : abortOn) {
                if (abort.isAssignableFrom(type)) {
                    return Verdict.ABORT;
                }
            }
            for (Class<? extends Throwable> retry : retryOn) {
                if (retry.isAssignableFrom(type)) {
                    return Verdict.RETRY;
                }
            }
            return Verdict.NOT_LISTED;
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
        new AsyncAttempts<>(this, call, null, fallback, recovery, result, start()).run();
        return result;
    }

    /**
     * Same as {@link #executeAsync(AsyncCall, FallbackPolicy, Recovery)}, with a classifier that decides
     * which results and exceptions are retried (see {@link #onFailure(int, Throwable, long, Classifier.Outcome)}
     * and {@link #onFailedResult}). When the retries give up on a rejected result, the future completes
     * with that result. An exception thrown by the classifier ends the retries as the call's failure.
     *
     * @param call one attempt: the next layer
     * @param classifier the method's classifier
     * @param fallback decides which failures get the fallback; {@code null} for none
     * @param recovery the generated fallback; {@code null} for none
     * @param <T> the result type
     * @return a future that completes with the first accepted result, the last rejected result, the fallback's
     *     result, or the last failure
     */
    public <T> CompletableFuture<T> executeAsync(AsyncCall<T> call, Classifier<? super T> classifier,
            @Nullable FallbackPolicy fallback, @Nullable Recovery<T> recovery) {
        CompletableFuture<T> result = new CompletableFuture<>();
        new AsyncAttempts<>(this, call, classifier, fallback, recovery, result, start()).run();
        return result;
    }

    /** The state of one asynchronous call across its attempts. Each attempt completes before the next starts. */
    private static final class AsyncAttempts<T> implements Runnable, BiConsumer<T, Throwable> {
        private final RetryPolicy policy;
        private final AsyncCall<T> call;
        private final @Nullable Classifier<? super T> classifier;
        private final @Nullable FallbackPolicy fallback;
        private final @Nullable Recovery<T> recovery;
        private final CompletableFuture<T> result;
        private final long start;
        private int attempts; // written by one attempt's callback before the next attempt is started

        AsyncAttempts(RetryPolicy policy, AsyncCall<T> call, @Nullable Classifier<? super T> classifier,
                @Nullable FallbackPolicy fallback, @Nullable Recovery<T> recovery, CompletableFuture<T> result,
                long start) {
            this.policy = policy;
            this.call = call;
            this.classifier = classifier;
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
            Classifier<? super T> classifier = this.classifier;
            if (failure == null) {
                if (classifier == null) {
                    result.complete(value);
                    return;
                }
                Classifier.Outcome outcome;
                try {
                    outcome = classifier.classify(value, null);
                } catch (Throwable classifierFailure) {
                    giveUp(classifierFailure, ++attempts);
                    return;
                }
                long delay = outcome == Classifier.Outcome.FAILURE ? policy.onFailedResult(++attempts, start) : NO_RETRY;
                if (delay >= 0) {
                    policy.environment.later(delay, this);
                } else {
                    result.complete(value); // accepted, or rejected with no retries left: the last result
                }
                return;
            }
            Throwable cause = AsyncSupport.unwrap(failure);
            long delay;
            if (classifier == null) {
                delay = policy.onFailure(++attempts, cause, start);
            } else {
                Classifier.Outcome outcome;
                try {
                    outcome = classifier.classify(null, cause);
                } catch (Throwable classifierFailure) {
                    giveUp(classifierFailure, ++attempts);
                    return;
                }
                delay = policy.onFailure(++attempts, cause, start, outcome);
            }
            if (delay >= 0) {
                policy.environment.later(delay, this);
            } else {
                giveUp(cause, attempts);
            }
        }

        private void giveUp(Throwable cause, int attempts) {
            if (fallback != null && recovery != null) {
                fallback.recoverOrFail(cause, attempts, recovery, result);
            } else {
                result.completeExceptionally(cause);
            }
        }
    }
}
