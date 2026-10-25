package dev.resilience4cata.examples.docs.retry;

import dev.resilience4cata.annotation.Backoff;
import dev.resilience4cata.annotation.Retry;

/** A fixed delay longer than the default maxDelay (10 s): invalid until maxDelay is raised too. */
public interface SlowPoller {

    @Retry(delay = "20s", backoff = Backoff.FIXED)
    String poll(String id);

    @Retry(delay = "20s", maxDelay = "20s", backoff = Backoff.FIXED)
    String pollFixed(String id);
}
