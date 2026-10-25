package dev.resilience4cata.generated;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * Shared pieces of the asynchronous policies: one timer thread for delays ({@link #later}, used by
 * retries, the rate limiter and the bulkhead), and the rules for turning what a delegate does into a
 * stage. Async timeouts, which are nearly always cancelled, use the lock-free {@link TimeoutTimer}
 * instead; {@code later} stays on a {@code ScheduledThreadPoolExecutor} because delays must be precise,
 * not rounded up to a tick.
 *
 * <p>The timer thread only schedules: every task it fires runs on a new virtual thread, so that user
 * callbacks attached to a completed future never run on (and never block) the timer.
 */
final class AsyncSupport {

    private AsyncSupport() {
    }

    private static final class Timer {
        static final ScheduledThreadPoolExecutor INSTANCE = create();

        private static ScheduledThreadPoolExecutor create() {
            ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1,
                    Thread.ofPlatform().daemon().name("resilience4cata-async-timer").factory());
            timer.setRemoveOnCancelPolicy(true); // most timeouts are cancelled: do not keep them until they expire
            return timer;
        }
    }

    /**
     * Calls the next layer. A delegate that throws instead of returning a failed stage, or that returns
     * {@code null}, is treated as a failed call.
     */
    // java:S2583: user code can return null despite the contract; this check turns it into a clear failure
    @SuppressWarnings("java:S2583")
    static <T> CompletionStage<T> call(AsyncCall<T> call) {
        @Nullable CompletionStage<T> stage; // user code may return null: checked below
        try {
            stage = call.call();
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
        if (stage == null) {
            return CompletableFuture.failedFuture(new NullPointerException("An asynchronous method returned null"
                    + " instead of a CompletionStage"));
        }
        return stage;
    }

    /** Completes {@code result} the same way as {@code stage}, with the failure unwrapped. */
    static <T> void relay(CompletionStage<T> stage, CompletableFuture<T> result) {
        stage.whenComplete((value, failure) -> {
            if (failure == null) {
                result.complete(value);
            } else {
                result.completeExceptionally(unwrap(failure));
            }
        });
    }

    /** The failure a stage carries, without the {@code CompletionException} wrappers that dependent stages add. */
    static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /** Runs {@code task} on a new virtual thread after {@code delayNanos}; at once if the delay is not positive. */
    @SuppressWarnings("java:S1452") // the JDK's own type for a scheduled Runnable; callers only cancel it
    static @Nullable ScheduledFuture<?> later(long delayNanos, Runnable task) {
        Objects.requireNonNull(task, "task");
        if (delayNanos <= 0) {
            Thread.ofVirtual().name("resilience4cata-async").start(task);
            return null;
        }
        return Timer.INSTANCE.schedule(() -> {
            Thread.ofVirtual().name("resilience4cata-async").start(task);
        }, delayNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Number of timer tasks waiting to fire: delayed tasks from {@link #later} plus async timeouts held
     * by {@link TimeoutTimer#shared()} (which drops cancelled ones within a tick); for tests.
     */
    static int queuedTimers() {
        return Timer.INSTANCE.getQueue().size() + TimeoutTimer.shared().pending();
    }

    /** Asks the producer of a stage that nobody waits for any more to stop, if it can be asked. */
    static void cancel(CompletionStage<?> stage) {
        if (stage instanceof Future<?> future) {
            future.cancel(true);
        }
    }
}
