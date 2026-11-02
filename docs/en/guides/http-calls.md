# Retry and circuit breaker for an HTTP call

A complete recipe for the most common case: one HTTP dependency, retried when it struggles, cut off when it is down, and never retried when the request itself is wrong.

The pieces come from [Retry](../policies/retry.md), [Circuit breaker](../policies/circuit-breaker.md) and [Timeout](../policies/timeout.md); this page puts them together and explains each choice. Every snippet is compiled and run against a real HTTP server in the project's tests.

## The interface :id=interface

<!-- snippet: http-calls-interface -->
```java
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
```

- **Two exception types for two kinds of failure.** A 5xx or a 429 means the service is struggling: worth another attempt, and a failure for the breaker. A 4xx means the request is wrong: another attempt gets the same answer, and the service is fine. Turning status codes into exceptions in the client is what lets the annotations tell them apart.
- **`@Retry`**: three attempts, with a random wait below 200 ms and then below 400 ms between them (exponential backoff with full jitter, the defaults), and never longer than 3 s in total (`maxDuration`). `retryOn` keeps its default, `Exception`, which covers connection errors, `ServerError` and timeouts; `abortOn = ClientError.class` wins over it.
- **`@CircuitBreaker`** with a `name`, so every method and every wrapper that calls the same service shares one breaker. `ignoreOn = ClientError.class`: a 4xx counts neither as a failure nor as a success, so callers sending bad requests cannot open it.
- **`@Timeout("1s")`** applies to each attempt, because the timeout sits inside the retry. A slow attempt is interrupted, counted as a failure by the breaker, and retried.

## The implementation :id=implementation

<!-- snippet: http-calls-implementation -->
```java
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public final class HttpInventoryClient implements InventoryClient {
    private final HttpClient http;
    private final URI base;

    public HttpInventoryClient(HttpClient http, URI base) {
        this.http = http;
        this.base = base;
    }

    @Override
    public int stock(String sku) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(base.resolve("/stock/" + sku)).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status >= 500 || status == 429) {
            throw new ServerError(status);
        }
        if (status >= 400) {
            throw new ClientError(status);
        }
        return Integer.parseInt(response.body().trim());
    }
}
```

The client only maps statuses to exceptions; it knows nothing about retries. Any client works the same way: Spring's `RestClient`, a generated OpenAPI client, OkHttp.

## Wiring it :id=wiring

<!-- snippet: http-calls-wiring -->
```java
static InventoryClient connect(URI base, ResilienceRegistry registry) {
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    return Resilient.of(InventoryClient.class, new HttpInventoryClient(http, base), registry);
}
```

Build the `ResilienceRegistry` once per application and share it: it holds the breaker's state. With [Spring Boot](spring.md), [Micronaut](micronaut.md) or [Quarkus](quarkus.md) the integration does this for you. Give the HTTP client its own connect timeout; `@Timeout` bounds the whole attempt, including the time to read the answer.

## What happens :id=what-happens

| The service answers | What the caller sees |
|---|---|
| 503, 503, then 200 | the value, after two retries |
| 404 | `ClientError` at once: one request, nothing recorded by the breaker |
| 500 until the breaker opens | `CallNotPermittedException` once the breaker is open; the retry stops instead of calling the service again |
| nothing for a second | the attempt is interrupted and retried; when the attempts or the 3 s budget run out, `CallTimeoutException` |

Add `@Fallback` if the caller should get a default instead of an exception ([Fallback](../policies/fallback.md)).

## Only retry what is safe to repeat :id=idempotency

A retry sends the same request again. That is safe for `GET`, `PUT` and `DELETE`, which give the same result when repeated, and not for a `POST` that creates an order or charges a card: a timeout does not tell you whether the first attempt went through. For those, either do not add `@Retry`, or send an idempotency key the service uses to drop duplicates. The breaker and the timeout are safe on any request.

## Choosing the numbers :id=numbers

- **Attempts and delays**: two or three attempts are usually enough; more only add load to a service that is already struggling. Keep `maxDuration` below the caller's own timeout.
- **Timeout**: a little above the service's normal slowest answer (its 99th percentile), not its average.
- **Breaker**: the defaults judge a window of 100 calls and open at 50% failures. For a service you call a few times a minute, lower `windowSize` and `minimumCalls`, or use a time window, or the breaker will never open.
- Change them per environment without touching the code: `instances.inventory.circuitBreaker.minimumCalls=20` in a properties file, or the matching environment variable ([Configuration](configuration.md)).
