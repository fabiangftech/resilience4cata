package dev.resilience4cata.examples.docs.retry;

import dev.resilience4cata.annotation.Retry;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Retry with exception lists and a time budget: the attributes table's example. */
public interface DocumentStore {

    // tag::retry-exceptions[]
    @Retry(maxAttempts = 5, delay = "500ms", maxDelay = "2s", maxDuration = "4s",
            retryOn = IOException.class, abortOn = FileNotFoundException.class)
    byte[] download(String path) throws IOException;
    // end::retry-exceptions[]
}
