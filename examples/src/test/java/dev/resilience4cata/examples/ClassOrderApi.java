package dev.resilience4cata.examples;

import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.Retry;

/** An annotated interface implemented by {@link ClassOrderService}: its annotations are the lowest level. */
@Retry(delay = "1ms", jitter = Jitter.NONE)
public interface ClassOrderApi {

    /** {@link ClassOrderService} overrides {@code maxAttempts}, and keeps the interface's delay. */
    @Retry(maxAttempts = 2)
    String status(String id);

    /** Not annotated in the class: the interface's three attempts apply. */
    @Retry(maxAttempts = 3)
    String quote(String id);
}
