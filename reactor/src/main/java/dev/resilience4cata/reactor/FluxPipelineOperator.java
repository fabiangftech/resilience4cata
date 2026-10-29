package dev.resilience4cata.reactor;

import dev.resilience4cata.Pipeline;
import dev.resilience4cata.generated.AsyncCall;
import dev.resilience4cata.generated.ElementListener;
import dev.resilience4cata.generated.StreamCall;
import dev.resilience4cata.reactor.generated.FluxCall;
import dev.resilience4cata.reactor.generated.Fluxes;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;

/**
 * A {@link Pipeline} as a {@code Flux} operator, for streams that cannot be annotated, such as a
 * {@code WebClient} call that returns many elements:
 *
 * <pre>{@code
 * FluxPipelineOperator<Quote> resilient = FluxPipelineOperator.of(pipeline);   // once, like the pipeline
 *
 * Flux<Quote> quotes = webClient.get().uri("/quotes")
 *         .retrieve().bodyToFlux(Quote.class)
 *         .transform(resilient);
 * }</pre>
 *
 * <p>Each subscription to the result is one call through the pipeline's policies, and each attempt
 * subscribes to the source again, with the subscriber's {@code Context} and demand. Until the source's
 * first element, a failure may be retried or replaced by the fallback's value (a stream of that one
 * element); the first element commits the call, and a later failure reaches the subscriber as it is. The
 * timeout restarts on every element. Cancelling the subscription cancels the call through every policy.
 * A pipeline with a hedge or a classifier cannot protect a {@code Flux}: its subscriber gets an
 * {@code IllegalStateException} ({@code R4C-RT-020}).
 *
 * <p>An operator is immutable and thread-safe; it holds nothing but its pipeline.
 *
 * @param <T> the element type
 */
public final class FluxPipelineOperator<T> implements UnaryOperator<Flux<T>> {

    private final Pipeline<T> pipeline;

    private FluxPipelineOperator(Pipeline<T> pipeline) {
        this.pipeline = pipeline;
    }

    /**
     * The operator of a pipeline.
     *
     * @param pipeline the policies to apply
     * @param <T> the element type
     * @return an operator for {@code Flux.transform}
     */
    public static <T> FluxPipelineOperator<T> of(Pipeline<T> pipeline) {
        return new FluxPipelineOperator<>(Objects.requireNonNull(pipeline, "pipeline"));
    }

    /**
     * Protects {@code source}: each subscription to the result runs the pipeline, and each attempt
     * subscribes to {@code source}.
     *
     * @param source the stream to protect
     * @return a {@code Flux} of the stream's elements, or the fallback's value
     */
    @Override
    public Flux<T> apply(Flux<T> source) {
        Objects.requireNonNull(source, "source");
        return defer(() -> source);
    }

    /**
     * Protects a stream that is built again for every attempt. A {@code call} that throws, or returns
     * {@code null}, is a failed attempt.
     *
     * @param call builds the stream to protect
     * @return a {@code Flux} of the stream's elements, or the fallback's value
     */
    public Flux<T> defer(Supplier<? extends Flux<? extends T>> call) {
        Objects.requireNonNull(call, "call");
        return Fluxes.defer(stream -> pipeline.executeStreamAsync(new Attempts<>(stream, call)));
    }

    @Override
    public String toString() {
        return "FluxPipelineOperator[" + pipeline + "]";
    }

    /** One subscription's call, as the pipeline drives it. */
    private record Attempts<T>(FluxCall<T> stream, Supplier<? extends Flux<? extends T>> source) implements StreamCall<T> {

        @Override
        public CompletionStage<Void> attempt(AsyncCall<Void> next) {
            return stream.attempt(next);
        }

        @Override
        public CompletionStage<Void> subscribe() {
            Flux<? extends T> flux;
            try {
                flux = source.get();
            } catch (RuntimeException | Error failure) {
                return CompletableFuture.failedFuture(failure);
            }
            return stream.subscribe(flux);
        }

        @Override
        public CompletionStage<Void> recover(@Nullable T value) {
            return stream.recover(value == null ? Flux.empty() : Flux.just(value));
        }

        @Override
        public void listen(ElementListener listener, boolean everyElement) {
            stream.listen(listener, everyElement);
        }
    }
}
