package dev.resilience4cata.examples.docs.adaptivelimit;

import dev.resilience4cata.annotation.AdaptiveLimit;
import dev.resilience4cata.annotation.LimitAlgorithm;
import dev.resilience4cata.annotation.Timeout;
import java.util.concurrent.CompletableFuture;

/** Adaptive limits with their attributes, a timeout inside, and an async method. */
public interface OrderClient {

    // tag::adaptive-limit-tuned[]
    @AdaptiveLimit(name = "orders", initialLimit = 10, maxLimit = 100, window = "500ms")
    @Timeout("2s") // inside the limit: a timeout is a drop, and the limit backs off
    String place(String order);
    // end::adaptive-limit-tuned[]

    // tag::adaptive-limit-aimd[]
    @AdaptiveLimit(name = "orders-aimd", algorithm = LimitAlgorithm.AIMD, initialLimit = 10,
            dropOn = {java.net.SocketTimeoutException.class, dev.resilience4cata.CallTimeoutException.class})
    String cancel(String order);
    // end::adaptive-limit-aimd[]

    // tag::adaptive-limit-async[]
    @AdaptiveLimit(name = "orders-async", initialLimit = 2)
    CompletableFuture<String> placeAsync(String order);
    // end::adaptive-limit-async[]
}
