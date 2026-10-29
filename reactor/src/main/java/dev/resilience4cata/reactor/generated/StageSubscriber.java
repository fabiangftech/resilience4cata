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
 * value cancels. It is cancelled before the future completes, so the policies' callbacks (a bulkhead freeing
 * its slot) run once the {@code Mono} is gone.
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

    /**
     * Completes with the failure. A failure after this subscriber cancelled (a timeout, a hedge, a caller
     * that gave up, racing the failure) is ignored: the call was abandoned on purpose, and Reactor's default
     * hook would log it as an error. A failure after the {@code Mono} already ended breaks the Reactive
     * Streams rules and goes to {@code onErrorDropped}.
     */
    @Override
    public void onError(Throwable failure) {
        if (!super.completeExceptionally(failure) && UPSTREAM.getVolatile(this) != CANCELLED) {
            Operators.onErrorDropped(failure, context);
        }
    }

    @Override
    public void onComplete() {
        super.complete(null);
    }

    /**
     * Cancels the subscription, then the future. In that order: completing the future runs the policies'
     * callbacks (a bulkhead frees its slot and may let the next call in), and by then this {@code Mono}
     * must be gone, so that a bulkhead never has more subscriptions running than slots.
     */
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        if (!isDone()) {
            cancelUpstream(); // harmless if the Mono completes meanwhile: a cancel after the end does nothing
        }
        return super.cancel(mayInterruptIfRunning);
    }

    /**
     * Completes the future with {@code failure}; a {@link CancellationException} (how a hedge cancels the
     * attempts that lost) first cancels the subscription, as {@link #cancel} does.
     */
    @Override
    public boolean completeExceptionally(Throwable failure) {
        if (failure instanceof CancellationException && !isDone()) {
            cancelUpstream();
        }
        return super.completeExceptionally(failure);
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
