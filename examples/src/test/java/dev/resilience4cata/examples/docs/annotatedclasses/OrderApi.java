package dev.resilience4cata.examples.docs.annotatedclasses;

import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.Retry;

/** The lowest level of the precedence example. */
// tag::annotated-classes-api[]
@Retry(delay = "10ms", jitter = Jitter.NONE)
public interface OrderApi {

    @Retry(maxAttempts = 2)
    String status(String id);

    @Retry(maxAttempts = 3)
    String quote(String id);
}
// end::annotated-classes-api[]
