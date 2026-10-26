package dev.resilience4cata.generated;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import org.jspecify.annotations.Nullable;

/**
 * The future an asynchronous policy returns: a {@code CompletableFuture} that also knows what runs
 * inside it, so that cancelling it reaches the call it protects (downward cancellation).
 *
 * <p>The link is what the policy is waiting for right now: the next layer's stage, a timer (a retry's
 * backoff, a rate limiter's delayed start), or a {@link OnCancel} hook of the policy (a queued bulkhead
 * waiter, an async timeout's deadline). It replaces the {@code new CompletableFuture<>()} each policy
 * already allocated: the extra field fits in the object's existing alignment padding, so a call
 * allocates the same objects and bytes as before.
 *
 * <p>{@link #cancel} (and {@link #completeExceptionally} with a {@link CancellationException}) first
 * swaps the link for a {@code CANCELLED} mark, then cancels what it held, and only then completes this
 * future. So when the inner stage ends because of the cancel, the policy's callback, which runs on the
 * cancelling thread, already sees {@link #cancelling()} and can release what it holds without
 * recording a failure; and when the user's own callbacks on this future run, the inner layers have
 * already let go (a bulkhead slot is free, a timer is cancelled). A policy that links something new
 * after the mark ({@link #link}) cancels it at once. Swap and link are atomic operations on the same
 * field, so exactly one of them sees the other: what was linked is cancelled exactly once.
 *
 * <p>The policies complete this future through {@link #fail}, never through the overridden
 * {@code completeExceptionally}, so that relaying an inner {@code CancellationException} upwards does
 * not start a downward cancel. Futures derived from this one ({@code thenApply} and the like) are plain
 * {@code CompletableFuture}s, as the JDK makes them: cancelling those does not reach this one.
 *
 * @param <T> the result type
 */
final class LinkedFuture<T> extends CompletableFuture<T> {

    /** A policy's own reaction to a cancel, linked instead of a future. */
    interface OnCancel {
        /** Called at most once per link, on the cancelling thread. */
        void cancelled();
    }

    private static final Object CANCELLED = new Object();

    /**
     * A link that cancels nothing: a policy links it to claim the next step (for example, starting a delayed
     * call) before taking it, so that a cancel either reaches the policy's hook first or finds the step taken.
     */
    static final Object STARTING = new Object();
    private static final VarHandle INNER;

    static {
        try {
            INNER = MethodHandles.lookup().findVarHandle(LinkedFuture.class, "inner", Object.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // java:S3077: read and swapped only through the INNER VarHandle
    @SuppressWarnings({"unused", "java:S3077"}) // accessed through INNER
    private volatile @Nullable Object inner;

    LinkedFuture() {
    }

    /**
     * A future linked to {@code inner} from the start. A plain store: nobody can cancel the future before it
     * is returned, and whoever cancels it later got it through a happens-before edge (the return to the
     * caller, a {@code whenComplete} registration, a timer hand-off), which publishes this store with it.
     * A release store here made C2 emit two extra full barriers ({@code dmb ish}) per call on aarch64,
     * about 5 ns per async layer on Graviton, for nothing: the policies never publish a future through a
     * data race, and a caller who did so could at worst lose the downward cancel, never a slot or a permit.
     */
    LinkedFuture(Object inner) {
        INNER.set(this, inner);
    }

    /**
     * Links {@code first} to a future created without a link, before it is returned: nobody can cancel it
     * yet, so a plain store is enough (see the linking constructor).
     */
    void linkFirst(Object first) {
        INNER.set(this, first);
    }

    /**
     * Links what the policy waits for now, instead of what it waited for before; if this future is being
     * cancelled already, cancels {@code next} at once instead. Called by one thread at a time (the policy
     * moves from one step to the next), concurrently with a cancel.
     *
     * @param next a stage, a timer, an {@link OnCancel} hook or {@link #STARTING}; {@code null} links nothing
     * @return {@code true} if linked; {@code false} if this future is being cancelled (and {@code next} was cancelled)
     */
    boolean link(@Nullable Object next) {
        if (next == null) {
            return !cancelling();
        }
        Object current = INNER.getOpaque(this);
        while (current != CANCELLED) {
            // Release: whoever swaps the mark in later and cancels next must see next fully built.
            Object witness = INNER.compareAndExchangeRelease(this, current, next);
            if (witness == current) {
                return true;
            }
            current = witness;
        }
        cancelInner(next);
        return false;
    }

    /** What is linked now (the {@code CANCELLED} mark once a cancel started); for {@link #replace}. */
    @Nullable Object linked() {
        return INNER.getOpaque(this);
    }

    /**
     * Links {@code next} only if {@code expected} is still linked: for a step that another thread may
     * already have moved past (a retry's backoff timer, whose task can start the next attempt, and link
     * it, before the timer is linked here). If a cancel came first, cancels {@code next}.
     *
     * @return {@code false} if this future is being cancelled (and {@code next} was cancelled)
     */
    boolean replace(@Nullable Object expected, @Nullable Object next) {
        if (next == null) {
            return !cancelling();
        }
        Object witness = expected == CANCELLED ? CANCELLED
                : INNER.compareAndExchangeRelease(this, expected, next);
        if (witness == CANCELLED) {
            cancelInner(next);
            return false;
        }
        return true; // linked, or someone linked something newer: leave that
    }

    /** Whether a cancel of this future has started; once true, stays true. */
    boolean cancelling() {
        return INNER.getOpaque(this) == CANCELLED;
    }

    /** Completes this future with a failure of the call (a policy's own completion, never a downward cancel). */
    boolean fail(Throwable failure) {
        return super.completeExceptionally(failure);
    }

    /**
     * Cancels this future and what runs inside it. The argument is ignored, as in
     * {@code CompletableFuture}: the inner stage is cancelled the same way whatever it is.
     */
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        if (!isDone()) {
            cancelDownward();
            super.completeExceptionally(new CancellationException());
        }
        return isCancelled();
    }

    /** Same as {@code CompletableFuture}; a {@link CancellationException} also cancels what runs inside. */
    @Override
    public boolean completeExceptionally(Throwable ex) {
        if (ex instanceof CancellationException && !isDone()) {
            cancelDownward();
        }
        return super.completeExceptionally(ex);
    }

    private void cancelDownward() {
        // Acquire: what was linked (with release) is cancelled below, so its fields must be visible.
        Object current = INNER.getAndSetAcquire(this, CANCELLED);
        if (current != CANCELLED && current != null) {
            cancelInner(current);
        }
    }

    /**
     * Cancels one linked object: a policy hook, a stage that is a {@link Future}, or a timer. A stage
     * that is not a {@code Future}, or refuses to be cancelled (such as a minimal stage), runs on; the
     * policies then end the call when it completes, as before.
     */
    static void cancelInner(Object linked) {
        if (linked instanceof OnCancel hook) {
            hook.cancelled();
        } else if (linked instanceof Future<?> future) {
            try {
                // A stage may interrupt its producer; a timer task only starts a virtual thread: no interrupt.
                future.cancel(linked instanceof CompletionStage<?>);
            } catch (UnsupportedOperationException refused) {
                // minimalCompletionStage() and the like cannot be cancelled: nothing more to do
            }
        }
    }
}
