package dev.resilience4cata.reactor.generated;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Operators;
import reactor.util.context.Context;

/**
 * Subscribes to one {@code Mono} and completes with its outcome: its value, {@code null} when it
 * completes empty, or its error. It is the stage that the policies wait for, so cancelling it (as a
 * timeout, a hedge or a cancelled caller does, through {@link #cancel} or
 * {@link #completeExceptionally} with a {@link CancellationException}) cancels the subscription.
 *
 * <p>The subscription is cancelled exactly once, whether the cancellation comes before or after
 * {@link #onSubscribe}: both sides swap {@link #upstream} atomically, and the side that finds the other's
 * value cancels.
 *
 * @param <T> the value type
 */
final class StageSubscriber<T> extends CompletableFuture<T> implements CoreSubscriber<T> {

    private static final VarHandle UPSTREAM;

    static {
        try {
            UPSTREAM = MethodHandles.lookup().findVarHandle(StageSubscriber.class, "upstream", Subscription.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** In {@link #upstream} once the future was cancelled: a subscription arriving later is cancelled at once. */
    private static final Subscription CANCELLED = new Subscription() {
        @Override
        public void request(long n) {
            // nothing to request: the future was cancelled
        }

        @Override
        public void cancel() {
            // already cancelled
        }
    };

    private final Context context;

    /** Null before {@link #onSubscribe}, then the subscription, or {@link #CANCELLED}. */
    @SuppressWarnings("unused") // accessed through UPSTREAM
    private volatile @Nullable Subscription upstream;

    StageSubscriber(Context context) {
        this.context = context;
    }

    @Override
    public Context currentContext() {
        return context;
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        if (UPSTREAM.compareAndSet(this, null, subscription)) {
            subscription.request(Long.MAX_VALUE);
        } else {
            // Cancelled before subscribing, or a second subscription (a broken publisher): drop it.
            subscription.cancel();
        }
    }

    @Override
    public void onNext(T value) {
        if (!super.complete(value)) {
            // Cancelled, or a value after the first: give the value to the discard hook (it may hold a buffer).
            Operators.onDiscard(value, context);
        }
    }

    @Override
    public void onError(Throwable failure) {
        if (!super.completeExceptionally(failure)) {
            Operators.onErrorDropped(failure, context);
        }
    }

    @Override
    public void onComplete() {
        super.complete(null);
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        boolean cancelled = super.cancel(mayInterruptIfRunning);
        if (cancelled) {
            cancelUpstream();
        }
        return cancelled;
    }

    /**
     * Completes the future with {@code failure}; a {@link CancellationException} (how a hedge cancels the
     * attempts that lost) also cancels the subscription.
     */
    @Override
    public boolean completeExceptionally(Throwable failure) {
        boolean completed = super.completeExceptionally(failure);
        if (completed && failure instanceof CancellationException) {
            cancelUpstream();
        }
        return completed;
    }

    private void cancelUpstream() {
        Subscription subscription = (Subscription) UPSTREAM.getAndSet(this, CANCELLED);
        if (subscription != null && subscription != CANCELLED) {
            subscription.cancel();
        }
    }

    /** True once the subscription was cancelled, or will be as soon as it arrives. For tests. */
    boolean upstreamCancelledForTest() {
        return UPSTREAM.getVolatile(this) == CANCELLED;
    }
}
