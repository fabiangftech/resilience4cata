package dev.resilience4cata.benchmark;

import dev.resilience4cata.annotation.Retry;

/**
 * The same work as {@link FakeService}, annotated as a class: protected through the generated subclass
 * {@code SubclassService$Resilient} instead of a wrapper. Not thread-safe.
 */
public class SubclassService {

    private final String value = "ok";
    private boolean failNext = true;

    @Retry(config = "bench")
    public String call() {
        return value;
    }

    /** Fails every other attempt, so each call succeeds on the second attempt. */
    @Retry(config = "bench")
    public String failOnce() {
        boolean fail = failNext;
        failNext = !fail;
        if (fail) {
            throw FakeService.FAILURE;
        }
        return value;
    }
}
