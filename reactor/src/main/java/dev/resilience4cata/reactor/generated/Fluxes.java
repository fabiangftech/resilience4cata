package dev.resilience4cata.reactor.generated;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Operators;

/**
 * Turns the asynchronous form of the policies into a {@code Flux}, for generated methods that return one.
 * A generated method returns {@link #defer} of nested {@code executeAsync} calls, as a {@code Mono} method
 * does, with three differences: the layers inside the retry or fallback (all of them when there is
 * neither) are one attempt, run by {@link FluxCall#attempt} with their own {@link FluxCall.Attempt}; the
 * timeout, circuit breaker and adaptive limit use their streaming form
 * ({@code executeStreamAsync(call, elements)}, with that attempt as the elements); and the delegate's
 * {@code Flux} is subscribed by {@link FluxCall.Attempt#subscribe}:
 *
 * <pre>{@code
 * return Fluxes.defer(r4c$stream -> this.retry0.executeAsync(() -> r4c$stream.attempt(r4c$attempt ->
 *         this.timeout0.executeStreamAsync(() -> r4c$attempt.subscribe(this.delegate.prices(p0)), r4c$attempt))));
 * }</pre>
 *
 * <p>Each subscription is one call through the policies. Elements go to the subscriber as each attempt
 * emits them, with the subscriber's demand. Until the first element, the policies act as for a
 * {@code Mono}: a failure may be retried or replaced by the fallback. The first element commits the call:
 * a later failure (a timeout between elements included) reaches the subscriber as it is. Cancelling the
 * subscription cancels the call through every policy.
 */
public final class Fluxes {

    private Fluxes() {
    }

    /**
     * A {@code Flux} that, on each subscription, starts the call through the policies and relays its
     * attempts' elements, then its end: completion, or the failure.
     *
     * @param call the policies around the delegate; called once per subscription
     * @param <T> the element type
     * @return the method's {@code Flux}
     */
    public static <T> Flux<T> defer(Function<? super FluxCall<T>, ? extends CompletionStage<Void>> call) {
        return Deferred.of(call);
    }

    private static final class Deferred<T> extends Flux<T> {
        private final Function<? super FluxCall<T>, ? extends CompletionStage<Void>> call;

        private Deferred(Function<? super FluxCall<T>, ? extends CompletionStage<Void>> call) {
            this.call = call;
        }

        static <T> Flux<T> of(Function<? super FluxCall<T>, ? extends CompletionStage<Void>> call) {
            return onAssembly(new Deferred<>(call));
        }

        @Override
        public void subscribe(CoreSubscriber<? super T> actual) {
            FluxCall<T> stream = new FluxCall<>(actual);
            actual.onSubscribe(stream);
            if (stream.isCancelled()) {
                return;
            }
            CompletableFuture<Void> future;
            try {
                future = call.apply(stream).toCompletableFuture();
            } catch (Throwable failure) {
                stream.fail(Operators.onOperatorError(failure, actual.currentContext()));
                return;
            }
            stream.start(future);
        }
    }
}
