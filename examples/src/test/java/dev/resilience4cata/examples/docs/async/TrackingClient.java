package dev.resilience4cata.examples.docs.async;

import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** The asynchronous interface of docs/{en,es}/guides/async.md. */
// tag::async-interface[]
public interface TrackingClient {

    @Retry(maxAttempts = 3, delay = "50ms")
    @Fallback(method = "unknown")
    CompletableFuture<String> track(String parcel);

    default CompletionStage<String> unknown(String parcel, IOException failure) {
        return CompletableFuture.completedFuture("unknown (" + failure.getMessage() + ")");
    }

    @Timeout("100ms")
    CompletionStage<String> eta(String parcel);
}
// end::async-interface[]
