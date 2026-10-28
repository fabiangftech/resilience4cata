package dev.resilience4cata.reactor.generated;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import reactor.core.CoreSubscriber;
import reactor.core.Fuseable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/**
 * Turns the asynchronous form of the policies into a {@code Mono}, for generated methods that return
 * one. A generated method returns {@link #defer} of the same nested {@code executeAsync} calls that a
 * {@code CompletionStage} method has, with the delegate's {@code Mono} turned into a stage by
 * {@link #stage}:
 *
 * <pre>{@code
 * return Monos.defer(r4c$context -> this.retry0.executeAsync(
 *         () -> Monos.stage(this.delegate.price(p0), r4c$context)));
 * }</pre>
 *
 * <p>So nothing runs before a subscription, every subscription is one call through the policies, the
 * subscriber's {@code Context} reaches the delegate's {@code Mono}, and cancelling the subscription
 * cancels the call through every policy (as cancelling the future of a {@code CompletionStage} method
 * does).
 */
public final class Monos {

    private Monos() {
    }

    /**
     * A {@code Mono} that, on each subscription, starts the call through the policies with the
     * subscriber's context, and emits its outcome: the value, nothing for a {@code null} result (an empty
     * {@code Mono}), or the failure. Cancelling the subscription cancels the call's future.
     *
     * @param call the policies around the delegate; called once per subscription
     * @param <T> the value type
     * @return the method's {@code Mono}
     */
    public static <T> Mono<T> defer(Function<? super ContextView, ? extends CompletionStage<T>> call) {
        return Deferred.of(call);
    }

    /**
     * Subscribes to the delegate's {@code Mono} with {@code context} and returns a stage of its outcome:
     * its value, {@code null} if it completes empty, or its error. Cancelling the stage (a timeout, a
     * hedge, a cancelled caller) cancels the subscription. A {@code null} {@code Mono} is a failed stage.
     *
     * @param mono what the delegate returned
     * @param context the context of the subscription to the generated method's {@code Mono}
     * @param <T> the value type
     * @return the stage the policies wait for
     */
    public static <T> CompletionStage<T> stage(@Nullable Mono<? extends T> mono, ContextView context) {
        if (mono == null) {
            return CompletableFuture.failedFuture(new NullPointerException(
                    "A method that returns a Mono returned null instead of a Mono"));
        }
        if (mono instanceof Fuseable.ScalarCallable<?> scalar) {
            return scalarStage(scalar);
        }
        StageSubscriber<T> subscriber = new StageSubscriber<>(
                context instanceof Context full ? full : Context.of(context));
        mono.subscribe(subscriber);
        if (subscriber.isDone() && !subscriber.isCompletedExceptionally()) {
            // Completed during subscribe (Mono.just, a cached value): a plain completed future lets the policies
            // take their already-complete shortcut (no timeout timer, no hedge).
            return CompletableFuture.completedFuture(subscriber.getNow(null));
        }
        return subscriber;
    }

    /**
     * The stage of a constant {@code Mono} ({@code Mono.just}, {@code Mono.empty}): its value is read
     * without subscribing, as Reactor's own operators do for scalar sources; there is nothing to cancel.
     */
    @SuppressWarnings("unchecked")
    private static <T> CompletionStage<T> scalarStage(Fuseable.ScalarCallable<?> scalar) {
        try {
            return CompletableFuture.completedFuture((T) scalar.call());
        } catch (Exception failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /**
     * The {@code Mono} of {@link #defer}. Not {@code Mono.fromFuture}: that one only looks at the future
     * once the subscriber requests, so a subscriber that has not requested yet would never see the
     * failure, which Reactive Streams requires (rule 1.4, checked by the TCK in {@code examples}).
     */
    private static final class Deferred<T> extends Mono<T> {
        private final Function<? super ContextView, ? extends CompletionStage<T>> call;

        private Deferred(Function<? super ContextView, ? extends CompletionStage<T>> call) {
            this.call = call;
        }

        static <T> Mono<T> of(Function<? super ContextView, ? extends CompletionStage<T>> call) {
            return onAssembly(new Deferred<>(call));
        }

        @Override
        public void subscribe(CoreSubscriber<? super T> actual) {
            CompletableFuture<T> future;
            try {
                future = call.apply(actual.currentContext()).toCompletableFuture();
            } catch (Throwable failure) {
                Operators.error(actual, Operators.onOperatorError(failure, actual.currentContext()));
                return;
            }
            Outcome<T> outcome = new Outcome<>(actual, future);
            actual.onSubscribe(outcome);
            switch (future.state()) {
                // Done already (a value there at subscription, a rejection): no callback to register.
                case SUCCESS -> outcome.accept(future.resultNow(), null);
                case FAILED -> outcome.accept(null, future.exceptionNow());
                case CANCELLED -> outcome.accept(null, new CancellationException());
                case RUNNING -> future.whenComplete(outcome);
            }
        }
    }

    /**
     * Delivers the future's outcome: a failure at once (no demand needed), a value when it is requested
     * ({@link Operators.MonoSubscriber#complete}), nothing for {@code null}. Cancelling the subscription
     * cancels the future, and so the call through every policy.
     */
    private static final class Outcome<T> extends Operators.MonoSubscriber<T, T> implements BiConsumer<T, Throwable> {
        private final CompletableFuture<T> future;

        Outcome(CoreSubscriber<? super T> actual, CompletableFuture<T> future) {
            super(actual);
            this.future = future;
        }

        @Override
        public void accept(@Nullable T result, @Nullable Throwable failure) {
            if (isCancelled()) {
                // The subscriber cancelled; the outcome (the cancel itself, or one that raced it) has nowhere to go. A
                // value may hold a resource; a failure is ignored, as the call was abandoned on purpose.
                if (result != null) {
                    Operators.onDiscard(result, actual.currentContext());
                }
                return;
            }
            if (failure != null) {
                actual.onError(failure instanceof CompletionException wrapped && wrapped.getCause() != null
                        ? wrapped.getCause() : failure);
            } else if (result == null) {
                actual.onComplete();
            } else {
                complete(result);
            }
        }

        @Override
        public void cancel() {
            super.cancel();
            future.cancel(true);
        }
    }
}
