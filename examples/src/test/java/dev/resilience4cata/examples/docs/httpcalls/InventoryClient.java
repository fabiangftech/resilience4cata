package dev.resilience4cata.examples.docs.httpcalls;

// The interface of docs/{en,es}/guides/http-calls.md; HttpCallsTest calls it over a real local HTTP server.
// tag::http-calls-interface[]
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import java.io.IOException;

public interface InventoryClient {

    @Retry(maxAttempts = 3, delay = "200ms", maxDuration = "3s", abortOn = ClientError.class)
    @CircuitBreaker(name = "inventory", ignoreOn = ClientError.class)
    @Timeout("1s")
    int stock(String sku) throws IOException, InterruptedException;

    /** A 4xx answer: the request is wrong, so a retry cannot help, and the service is not failing. */
    final class ClientError extends IOException {
        private static final long serialVersionUID = 1L;

        public ClientError(int status) {
            super("HTTP " + status);
        }
    }

    /** A 5xx or 429 answer: the service is struggling. Worth a retry, and a failure for the breaker. */
    final class ServerError extends IOException {
        private static final long serialVersionUID = 1L;

        public ServerError(int status) {
            super("HTTP " + status);
        }
    }
}
// end::http-calls-interface[]
