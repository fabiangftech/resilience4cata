package dev.resilience4cata.event;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/** Events of a retry. A call that succeeds, at the first attempt or after retries, publishes nothing. */
public sealed interface RetryEvent extends ResilienceEvent {

    /**
     * An attempt failed and will be retried after {@code delay}.
     *
     * @param instance the retry's id
     * @param nanoTime when the attempt failed
     * @param attempt the number of the attempt that failed, starting at 1
     * @param delay the wait before the next attempt, jitter included
     * @param failure what the attempt threw, or {@code null} if it returned a result that the method's
     *     classifier rejected
     */
    record Retrying(String instance, long nanoTime, int attempt, Duration delay, @Nullable Throwable failure)
            implements RetryEvent {
    }

    /**
     * An attempt failed and will not be retried: the failure reaches the caller (or the fallback). Not
     * published when the retry is disabled ({@code enabled = false}).
     *
     * @param instance the retry's id
     * @param nanoTime when the last attempt failed
     * @param attempts number of attempts made, including the last one
     * @param reason why no further attempt is made
     * @param failure what the last attempt threw, or {@code null} if it returned a result that the
     *     method's classifier rejected (that result is then returned)
     */
    record GaveUp(String instance, long nanoTime, int attempts, Reason reason, @Nullable Throwable failure)
            implements RetryEvent {
    }

    /** Why a retry gave up. */
    enum Reason {
        /** {@code maxAttempts} attempts were made. */
        MAX_ATTEMPTS,
        /** Another attempt would end after {@code maxDuration}. */
        MAX_DURATION,
        /** The failure is not retryable: not in {@code retryOn}, or in {@code abortOn}. */
        NOT_RETRYABLE,
        /** The thread was interrupted. */
        INTERRUPTED
    }
}
