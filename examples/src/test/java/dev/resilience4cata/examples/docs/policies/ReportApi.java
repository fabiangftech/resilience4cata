package dev.resilience4cata.examples.docs.policies;

// tag::policies-order[]
import static dev.resilience4cata.annotation.Layer.RETRY;
import static dev.resilience4cata.annotation.Layer.TIMEOUT;

import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.PolicyOrder;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;

public interface ReportApi {

    @PolicyOrder({TIMEOUT, RETRY})                // Fallback -> Timeout -> Retry -> ...: one deadline for every attempt
    @Timeout("1s")
    @Retry(maxAttempts = 10, delay = "300ms", jitter = Jitter.NONE)
    String report(String id);
}
// end::policies-order[]
