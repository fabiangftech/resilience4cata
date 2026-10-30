package dev.resilience4cata.generated;

import java.util.concurrent.CompletionStage;

/**
 * One attempt of a stream through a {@link dev.resilience4cata.Pipeline}: the elements its policies listen
 * to, and its subscription to the source. Created by {@link StreamCall#attempt} for that attempt only, so a
 * layer that starts late (a bulkhead hand-off after a timeout) subscribes as the attempt it belonged to.
 */
public interface StreamAttempt extends Elements {

    /**
     * Subscribes this attempt to the source stream.
     *
     * @return a stage that ends when the attempt's stream does
     */
    CompletionStage<Void> subscribe();
}
