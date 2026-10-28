package dev.resilience4cata.reactor.generated;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;
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
        return Mono.deferContextual(context -> Mono.fromFuture(() -> call.apply(context).toCompletableFuture(), false));
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
}
