package dev.resilience4cata.generated;

import java.util.concurrent.CompletionStage;

/**
 * The policies of one attempt of a stream, inside the retry or the fallback: given the attempt, they start
 * it and return the stage of its stream. See {@link StreamCall#attempt}.
 */
@FunctionalInterface
public interface AttemptLayers {

    /**
     * Runs the attempt's policies, ending with {@link StreamAttempt#subscribe()}.
     *
     * @param attempt this attempt
     * @return the stage of the attempt's stream
     * @throws Throwable if a layer fails before returning a stage
     */
    // java:S112: like AsyncCall, a layer may fail with anything
    @SuppressWarnings("java:S112")
    CompletionStage<Void> start(StreamAttempt attempt) throws Throwable;
}
