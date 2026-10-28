package dev.resilience4cata.reactor;

import dev.resilience4cata.Pipeline;
import dev.resilience4cata.reactor.generated.Monos;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import reactor.core.publisher.Mono;

/**
 * A {@link Pipeline} as a {@code Mono} operator, for reactive code that cannot be annotated, such as a
 * {@code WebClient} call:
 *
 * <pre>{@code
 * PipelineOperator<Price> resilient = PipelineOperator.of(pipeline);   // once, like the pipeline
 *
 * Mono<Price> price = webClient.get().uri("/prices/{sku}", sku)
 *         .retrieve().bodyToMono(Price.class)
 *         .transform(resilient);
 * }</pre>
 *
 * <p>Each subscription to the result is one call through the pipeline's policies, in their asynchronous
 * form ({@link Pipeline#executeAsync}): every attempt of a retry or hedge subscribes to the source
 * {@code Mono} again, the subscriber's {@code Context} reaches it, and cancelling the subscription
 * cancels the call through every policy. A source that does not run again when it is subscribed to
 * again (a {@code cache()}d or hot {@code Mono}) gives every attempt the same outcome; build the source
 * per attempt with {@link #defer} instead.
 *
 * <p>An operator is immutable and thread-safe; it holds nothing but its pipeline.
 *
 * @param <T> the value type
 */
public final class PipelineOperator<T> implements UnaryOperator<Mono<T>> {

    private final Pipeline<T> pipeline;

    private PipelineOperator(Pipeline<T> pipeline) {
        this.pipeline = pipeline;
    }

    /**
     * The operator of a pipeline.
     *
     * @param pipeline the policies to apply
     * @param <T> the value type
     * @return an operator for {@code Mono.transform}
     */
    public static <T> PipelineOperator<T> of(Pipeline<T> pipeline) {
        return new PipelineOperator<>(Objects.requireNonNull(pipeline, "pipeline"));
    }

    /**
     * Protects {@code source}: each subscription to the result runs the pipeline, and each attempt
     * subscribes to {@code source}.
     *
     * @param source the call to protect
     * @return a {@code Mono} of the call's value, the fallback's, or the last failure
     */
    @Override
    public Mono<T> apply(Mono<T> source) {
        Objects.requireNonNull(source, "source");
        return defer(() -> source);
    }

    /**
     * Protects a call that is built again for every attempt: each subscription to the result runs the
     * pipeline, and each attempt calls {@code call} and subscribes to the {@code Mono} it returns. A
     * {@code call} that throws, or returns {@code null}, is a failed attempt.
     *
     * @param call builds the call to protect
     * @return a {@code Mono} of the call's value, the fallback's, or the last failure
     */
    public Mono<T> defer(Supplier<? extends Mono<? extends T>> call) {
        Objects.requireNonNull(call, "call");
        return Monos.defer(context -> pipeline.executeAsync(() -> Monos.stage(call.get(), context)));
    }

    @Override
    public String toString() {
        return "PipelineOperator[" + pipeline + "]";
    }
}
