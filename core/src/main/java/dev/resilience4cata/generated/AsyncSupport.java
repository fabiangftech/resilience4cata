package dev.resilience4cata.generated;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
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
                    Threads.daemons("resilience4cata-async-timer"));
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

    /**
     * True if {@code stage} already completed normally: exactly a {@code CompletableFuture}, or an inner policy's
     * {@link LinkedFuture} (subclasses, such as minimal stages, may not support {@code state()}). A policy may then
     * record the outcome at once and return {@link #copy}, with no link and no callback.
     */
    static boolean succeeded(CompletionStage<?> stage) {
        Class<?> type = stage.getClass();
        return type == Ended.class || (type == CompletableFuture.class || type == LinkedFuture.class)
                && ((CompletableFuture<?>) stage).state() == java.util.concurrent.Future.State.SUCCESS;
    }

    /**
     * A completed copy of a stage for which {@link #succeeded} holds: the caller never gets the inner future. The
     * shared {@link Ended} stage is immutable, so it is returned as it is.
     */
    @SuppressWarnings("unchecked")
    static <T> CompletableFuture<T> copy(CompletionStage<T> stage) {
        if (stage.getClass() == Ended.class) {
            return (CompletableFuture<T>) stage;
        }
        return CompletableFuture.completedFuture(((CompletableFuture<T>) stage).resultNow());
    }

    /** Completes {@code result} the same way as {@code stage}, with the failure unwrapped. */
    static <T> void relay(CompletionStage<T> stage, LinkedFuture<T> result) {
        stage.whenComplete((value, failure) -> {
            if (failure == null) {
                result.complete(value);
            } else {
                result.fail(unwrap(failure));
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

    /**
     * Runs {@code task} on a new virtual thread after {@code delayNanos}; at once if the delay is not positive. The
     * task runs with the caller's context class loader and no inherited thread-local values ({@link Threads}).
     */
    @SuppressWarnings("java:S1452") // the JDK's own type for a scheduled Runnable; callers only cancel it
    static @Nullable ScheduledFuture<?> later(long delayNanos, Runnable task) {
        Objects.requireNonNull(task, "task");
        ClassLoader loader = Threads.callers();
        if (delayNanos <= 0) {
            Threads.startVirtual("resilience4cata-async", loader, task);
            return null;
        }
        return Timer.INSTANCE.schedule(() -> Threads.startVirtual("resilience4cata-async", loader, task),
                delayNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Number of timer tasks waiting to fire: delayed tasks from {@link #later} plus async timeouts held
     * by {@link TimeoutTimer#shared()} (which drops cancelled ones within a tick); for tests.
     */
    static int queuedTimers() {
        return Timer.INSTANCE.getQueue().size() + TimeoutTimer.shared().pending();
    }
}
