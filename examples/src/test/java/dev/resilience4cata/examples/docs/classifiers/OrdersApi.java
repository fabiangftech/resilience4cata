package dev.resilience4cata.examples.docs.classifiers;

import dev.resilience4cata.ResultRejectedException;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.ExhaustedResult;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Retry;
import java.io.IOException;

/** Classifiers on retry and breaker, and what happens when the retries give up on a result. */
public interface OrdersApi {

    // tag::classifiers-usage[]
    @Retry(maxAttempts = 3, delay = "100ms", classifier = StatusClassifier.class)
    @CircuitBreaker(name = "orders", classifier = StatusClassifier.class)
    Response get(String orderId) throws IOException;
    // end::classifiers-usage[]

    // tag::classifiers-exhausted[]
    @Retry(maxAttempts = 3, delay = "100ms", classifier = StatusClassifier.class,
            onExhaustedResult = ExhaustedResult.THROW)
    @Fallback(method = "unavailable")
    Response submit(String orderId) throws IOException;

    default Response unavailable(String orderId, ResultRejectedException rejected) {
        Response last = rejected.result(Response.class);   // the last rejected result
        return new Response(503, "gave up after " + rejected.attempts() + " attempts: " + last.body());
    }
    // end::classifiers-exhausted[]
}
