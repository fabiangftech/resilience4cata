package dev.resilience4cata.reactor.generated;

import dev.resilience4cata.generated.ElementListener;
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
 * One attempt of a {@link FluxCall}: a subscription to the delegate's {@code Flux} whose elements go to
 * the call's subscriber, and a future that ends when the attempt's stream does ({@code null} when it
 * completes, its error otherwise), which the policies wait for.
 *
 * <p>Its first element commits the call, if this attempt still may ({@link FluxCall#commit}); if a timeout
 * gave up on it and the retry moved on, the element is discarded, and so are the rest. Then the policies'
 * listeners hear about it. Cancelling the future (a timeout, a cancelled subscriber) cancels the
 * subscription first, as {@link StageSubscriber} does, so that a bulkhead frees its slot only once the
 * stream is cancelled.
 *
 * @param <T> the element type
 */
final class FluxAttempt<T> extends CompletableFuture<Void> implements CoreSubscriber<T> {

    private static final VarHandle UPSTREAM;

    static {
        try {
            UPSTREAM = MethodHandles.lookup().findVarHandle(FluxAttempt.class, "upstream", Subscription.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final Subscription CANCELLED = new Subscription() {
        @Override
        public void request(long n) {
            // nothing to request: the attempt was cancelled
        }

        @Override
        public void cancel() {
            // already cancelled
        }
    };

    private final FluxCall<T> call;
    private final int number;
    private final @Nullable ElementListener every;
    private final @Nullable ElementListener first;
    private final @Nullable ElementListener second;
    private final Context context;

    @SuppressWarnings("unused") // accessed through UPSTREAM
    private volatile @Nullable Subscription upstream;

    // Only touched by onNext, which Reactive Streams calls one element at a time.
    private boolean emitted;
    private boolean discarding;

    FluxAttempt(FluxCall<T> call, int number, @Nullable ElementListener every, @Nullable ElementListener first,
            @Nullable ElementListener second) {
        this.call = call;
        this.number = number;
        this.every = every;
        this.first = first;
        this.second = second;
        this.context = call.actual.currentContext();
    }

    @Override
    public Context currentContext() {
        return context;
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        if (UPSTREAM.compareAndSet(this, null, subscription)) {
            call.arbitrate(subscription);
        } else {
            subscription.cancel(); // cancelled before subscribing, or a second subscription (a broken publisher)
        }
    }

    @Override
    public void onNext(T element) {
        if (!emitted) {
            if (discarding || !call.commit(number)) {
                discarding = true; // given up on: another attempt may be running
                Operators.onDiscard(element, context);
                return;
            }
            emitted = true;
            if (first != null) {
                first.element();
            }
            if (second != null) {
                second.element();
            }
        }
        if (every != null) {
            every.element();
        }
        call.emit(element);
    }

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

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        if (!isDone()) {
            cancelUpstream(); // before completing: what waits for this future lets go after the stream stopped
        }
        return super.cancel(mayInterruptIfRunning);
    }

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

    /** Test seam: whether the attempt's subscription was cancelled. */
    boolean upstreamCancelledForTest() {
        return UPSTREAM.getVolatile(this) == CANCELLED;
    }
}
