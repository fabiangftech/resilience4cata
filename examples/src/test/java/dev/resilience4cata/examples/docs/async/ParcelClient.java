package dev.resilience4cata.examples.docs.async;

import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.CompletionStage;

/** A wildcard stage: the policies work with its upper bound. */
// tag::async-wildcard[]
public interface ParcelClient {

    @Retry(maxAttempts = 2, delay = "10ms")
    @Fallback(method = "noWeight")
    CompletionStage<? extends Number> weight(String parcel);

    default Integer noWeight(String parcel, Exception failure) {
        return 0;
    }
}
// end::async-wildcard[]
