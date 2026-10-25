package dev.resilience4cata.examples.docs.classifiers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.CircuitBreakerHandle;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.Pipeline;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.ResultRejectedException;
import dev.resilience4cata.event.RetryEvent;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Every claim and snippet of {@code docs/en/guides/classifiers.md}. */
class ClassifiersPageTest {

    private final ManualClock clock = new ManualClock();
    private final List<RetryEvent.GaveUp> gaveUp = new CopyOnWriteArrayList<>();
    private final ResilienceRegistry registry = ResilienceTesting.registry(clock)
            .listen(RetryEvent.GaveUp.class, gaveUp::add)
            .build();
    private final Deque<Object> script = new ArrayDeque<>();
    private final AtomicInteger calls = new AtomicInteger();

    private Response next() throws IOException {
        calls.incrementAndGet();
        Object step = script.isEmpty() ? new Response(200, "ok") : script.removeFirst();
        if (step instanceof IOException e) {
            throw e;
        }
        if (step instanceof RuntimeException e) {
            throw e;
        }
        return (Response) step;
    }

    private final OrdersApi backend = new OrdersApi() {
        @Override
        public Response get(String orderId) throws IOException {
            return next();
        }

        @Override
        public Response submit(String orderId) throws IOException {
            return next();
        }
    };

    @Test
    void failureResultsAreRetried() throws IOException {
        OrdersApi orders = Resilient.of(OrdersApi.class, backend, registry);
        script.add(new Response(503, "busy"));
        script.add(new IOException("reset"));
        assertThat(orders.get("o-1").status()).isEqualTo(200);
        assertThat(calls).hasValue(3);
    }

    @Test
    void ignoreAndSuccessEndTheRetries() throws IOException {
        OrdersApi orders = Resilient.of(OrdersApi.class, backend, registry);
        script.add(new Response(404, "missing"));
        assertThat(orders.get("o-1").status()).isEqualTo(404);
        assertThat(calls).hasValue(1);

        script.add(new IllegalArgumentException("bad id")); // IGNORE for an exception: rethrown, not retried
        assertThatThrownBy(() -> orders.get("o-1")).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).hasValue(2);
        CircuitBreakerHandle breaker = registry.circuitBreaker("orders");
        assertThat(breaker.snapshot().bufferedCalls()).isZero(); // 404 and the IGNOREd exception: not recorded
    }

    @Test
    void byDefaultTheLastRejectedResultIsReturned() throws IOException {
        OrdersApi orders = Resilient.of(OrdersApi.class, backend, registry);
        script.add(new Response(503, "a"));
        script.add(new Response(503, "b"));
        script.add(new Response(503, "c"));
        assertThat(orders.get("o-1")).isEqualTo(new Response(503, "c"));
        assertThat(calls).hasValue(3);
        assertThat(registry.circuitBreaker("orders").snapshot().failedCalls()).isEqualTo(3);
        assertThat(gaveUp.getLast().reason()).isEqualTo(RetryEvent.Reason.MAX_ATTEMPTS);
        assertThat(gaveUp.getLast().failure()).isNull();
    }

    @Test
    void throwOnExhaustedResultGoesToTheFallback() throws IOException {
        OrdersApi orders = Resilient.of(OrdersApi.class, backend, registry);
        script.add(new Response(503, "a"));
        script.add(new Response(503, "b"));
        script.add(new Response(429, "slow down"));
        assertThat(orders.submit("o-1")).isEqualTo(new Response(503, "gave up after 3 attempts: slow down"));
    }

    @Test
    void aThrowingClassifierFailsTheCall() {
        AtomicInteger runs = new AtomicInteger();
        FragileApi fragile = Resilient.of(FragileApi.class, new FragileApi() {
            @Override
            public Response get(String id) {
                runs.incrementAndGet();
                return new Response(200, null);
            }

            @Override
            public Response recorded(String id) {
                return get(id);
            }

            @Override
            public Response withFallback(String id) {
                return get(id);
            }
        }, registry);

        assertThatThrownBy(() -> fragile.get("x")).isInstanceOf(IllegalStateException.class).hasMessage("classifier bug");
        assertThat(runs).hasValue(1); // not retried
        assertThat(gaveUp.getLast().reason()).isEqualTo(RetryEvent.Reason.CLASSIFIER_FAILED);

        assertThatThrownBy(() -> fragile.recorded("x")).isInstanceOf(IllegalStateException.class);
        assertThat(registry.circuitBreaker("fragile").state()).isEqualTo(CircuitBreakerState.OPEN); // recorded as a failure

        assertThat(fragile.withFallback("y").body()).isEqualTo("recovered from classifier bug");
    }

    @Test
    void pipeline() throws IOException {
        OrdersApi orders = backend;
        script.add(new Response(503, "busy"));
        // tag::classifiers-pipeline[]
        Pipeline<Response> get = Pipeline.builder(registry, "orders-pipeline", Response.class)
                .retry(r -> r.maxAttempts(3))
                .circuitBreaker()
                .classifier(new StatusClassifier())              // both policies
                .onExhaustedResult(Pipeline.ExhaustedResult.THROW)
                .build();

        Response response = get.execute(() -> orders.get("o-1"));
        // end::classifiers-pipeline[]
        assertThat(response.status()).isEqualTo(200);
        assertThat(calls).hasValue(2);

        script.add(new Response(503, "a"));
        script.add(new Response(503, "b"));
        script.add(new Response(503, "c"));
        assertThatThrownBy(() -> get.execute(() -> orders.get("o-2")))
                .isInstanceOf(ResultRejectedException.class)
                .satisfies(e -> assertThat(((ResultRejectedException) e).attempts()).isEqualTo(3));
    }
}
