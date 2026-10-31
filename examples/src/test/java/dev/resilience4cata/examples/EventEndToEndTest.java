package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.BulkheadFullException;
import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.CircuitBreakerSnapshot;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.RateLimitExceededException;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.ResilienceSnapshot;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.event.BulkheadEvent;
import dev.resilience4cata.event.CircuitBreakerEvent;
import dev.resilience4cata.event.FallbackEvent;
import dev.resilience4cata.event.RateLimiterEvent;
import dev.resilience4cata.event.ResilienceEvent;
import dev.resilience4cata.event.RetryEvent;
import dev.resilience4cata.event.TimeoutEvent;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class EventEndToEndTest {

    private final List<ResilienceEvent> events = new CopyOnWriteArrayList<>();
    private final List<CircuitBreakerEvent.StateTransition> paymentTransitions = new CopyOnWriteArrayList<>();
    private final ResilienceRegistry registry = ResilienceRegistry.builder()
            .listen(ResilienceEvent.class, events::add)
            .listen("payments", CircuitBreakerEvent.StateTransition.class, paymentTransitions::add)
            .build();

    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch entered = new CountDownLatch(1);

    private final EventClient client = Resilient.of(EventClient.class, new EventClient() {
        @Override
        public String order(String id) throws IOException {
            throw new IOException("down");
        }

        @Override
        public String pay(String id) throws IOException {
            throw new IOException("declined");
        }

        @Override
        // java:S2925: the call must really block so that the timeout interrupts it
        @SuppressWarnings("java:S2925")
        public String slow(String id) throws InterruptedException {
            Thread.sleep(10_000);
            return id;
        }

        @Override
        public String report(String id) throws InterruptedException {
            entered.countDown();
            release.await();
            return id;
        }

        @Override
        public String search(String query) {
            return query;
        }
    }, registry);

    @Test
    void retriesAndTheFallbackArePublished() throws IOException {
        assertThat(client.order("1")).isEqualTo("cached 1");

        assertThat(events).extracting(e -> e.getClass().getSimpleName())
                .containsExactly("Retrying", "Retrying", "GaveUp", "Applied");
        assertThat(events.get(1)).isInstanceOfSatisfying(RetryEvent.Retrying.class, r -> {
            assertThat(r.id()).isEqualTo("orders");
            assertThat(r.attempt()).isEqualTo(2);
        });
        assertThat(events.get(3)).isInstanceOfSatisfying(FallbackEvent.Applied.class, f -> {
            assertThat(f.id()).isEqualTo("dev.resilience4cata.examples.EventClient#order(java.lang.String)");
            assertThat(f.failure()).hasMessage("down");
        });
    }

    @Test
    void circuitBreakerEventsAndSnapshot() {
        assertThatThrownBy(() -> client.pay("1")).isInstanceOf(IOException.class);
        CircuitBreakerSnapshot halfway = registry.circuitBreaker("payments").snapshot();
        assertThat(halfway.bufferedCalls()).isEqualTo(1);
        assertThat(halfway.failureRate()).as("below minimumCalls").isEqualTo(-1f);

        assertThatThrownBy(() -> client.pay("2")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> client.pay("3")).isInstanceOf(CallNotPermittedException.class);

        assertThat(paymentTransitions).containsExactly(new CircuitBreakerEvent.StateTransition("payments",
                paymentTransitions.get(0).nanoTime(), CircuitBreakerState.CLOSED, CircuitBreakerState.OPEN));
        assertThat(events).filteredOn(CircuitBreakerEvent.CallNotPermitted.class::isInstance).hasSize(1);
        assertThat(registry.snapshot().circuitBreakers()).singleElement()
                .isEqualTo(new CircuitBreakerSnapshot("payments", CircuitBreakerState.OPEN, -1f, -1f, 0, 0, 0));
    }

    @Test
    void aTimeoutIsPublished() {
        assertThatThrownBy(() -> client.slow("1")).isInstanceOf(CallTimeoutException.class);
        assertThat(events).singleElement().isInstanceOf(TimeoutEvent.TimedOut.class);
    }

    @Test
    void bulkheadRejectionsAndSnapshot() throws Exception {
        Thread holder = Thread.ofVirtual().start(() -> {
            try {
                client.report("held");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(registry.bulkhead("reports").snapshot().concurrentCalls()).isEqualTo(1);
        assertThatThrownBy(() -> client.report("2")).isInstanceOf(BulkheadFullException.class);
        release.countDown();
        holder.join();

        assertThat(events).singleElement().isEqualTo(new BulkheadEvent.Rejected("reports", events.get(0).nanoTime(), 1));
        assertThat(registry.snapshot().bulkheads()).singleElement().satisfies(b -> assertThat(b.concurrentCalls()).isZero());
    }

    @Test
    void rateLimiterRejectionsAndSnapshot() {
        assertThat(client.search("a")).isEqualTo("a");
        assertThatThrownBy(() -> client.search("b")).isInstanceOf(RateLimitExceededException.class);

        assertThat(events).singleElement().isInstanceOf(RateLimiterEvent.Rejected.class);
        ResilienceSnapshot snapshot = registry.snapshot();
        assertThat(snapshot.rateLimiters()).singleElement().satisfies(r -> assertThat(r.availablePermits()).isZero());
        assertThat(snapshot.retries()).extracting(r -> r.id()).containsExactly("orders");
        assertThat(snapshot.timeouts()).hasSize(1);
    }
}
