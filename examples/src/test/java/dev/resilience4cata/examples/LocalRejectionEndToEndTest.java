package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.BulkheadFullException;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.RateLimitExceededException;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/** A full bulkhead or rate limiter inside a breaker is the caller's own load: the breaker stays closed. */
class LocalRejectionEndToEndTest {

    private final ResilienceRegistry registry = ResilienceRegistry.builder().build();
    private final CountDownLatch inside = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    private final LocalRejectionClient client = Resilient.of(LocalRejectionClient.class, new LocalRejectionClient() {
        @Override
        public String fetch(String id) {
            if (id.equals("hold")) {
                inside.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return id;
        }

        @Override
        public String quote(String id) {
            return id;
        }
    }, registry);

    @Test
    void aFullBulkheadDoesNotOpenTheBreaker() throws InterruptedException {
        Thread holder = Thread.ofVirtual().start(() -> client.fetch("hold"));
        inside.await();
        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(() -> client.fetch("x")).isInstanceOf(BulkheadFullException.class);
        }
        release.countDown();
        holder.join();

        assertThat(registry.circuitBreaker("local-bulkhead").state()).isEqualTo(CircuitBreakerState.CLOSED);
        assertThat(client.fetch("after")).isEqualTo("after");
    }

    @Test
    void aRateLimiterDoesNotOpenTheBreakerEvenWhenRecordOnCoversIt() {
        assertThat(client.quote("first")).isEqualTo("first");
        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(() -> client.quote("x")).isInstanceOf(RateLimitExceededException.class);
        }
        assertThat(registry.circuitBreaker("local-rate").state()).isEqualTo(CircuitBreakerState.CLOSED);
    }
}
