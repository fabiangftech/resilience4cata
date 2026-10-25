package dev.resilience4cata.examples.docs.policies;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.RetryConfig;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Every claim and snippet of {@code docs/en/policies/README.md}. */
class PoliciesPageTest {

    private final ManualClock clock = new ManualClock();
    private final ResilienceRegistry registry = ResilienceTesting.registry(clock)
            .retryDefaults(r -> r.jitter(RetryConfig.Jitter.NONE))
            .build();
    private final AtomicInteger calls = new AtomicInteger();

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void anOpenBreakerStopsTheRetries() {
        // tag::policies-open-breaker[]
        CheckoutApi checkout = Resilient.of(CheckoutApi.class, cartId -> {
            calls.incrementAndGet();
            return "placed " + cartId;
        }, registry);
        registry.circuitBreaker("checkout").forceOpen();

        assertThat(checkout.placeOrder("cart-1")).isEqualTo("queued cart-1");  // the fallback answered
        assertThat(calls).hasValue(0);                  // the breaker rejected the first attempt...
        assertThat(clock.elapsed()).isZero();           // ...and the retry did not wait to try again
        // end::policies-open-breaker[]
    }

    @Test
    void retriesThenFallback() {
        CheckoutApi checkout = Resilient.of(CheckoutApi.class, cartId -> {
            calls.incrementAndGet();
            throw new IllegalStateException("down");
        }, registry);
        assertThat(checkout.placeOrder("cart-1")).isEqualTo("queued cart-1");
        assertThat(calls).hasValue(3);
        assertThat(clock.elapsed()).isEqualTo(Duration.ofMillis(600)); // 200 ms + 400 ms
        assertThat(registry.circuitBreaker("checkout").snapshot().failedCalls()).isEqualTo(3); // each attempt
    }

    private final LimitedApi backend = new LimitedApi() {
        @Override
        public String limited(String id) {
            calls.incrementAndGet();
            return "ok";
        }

        @Override
        public String retriedRejections(String id) {
            return limited(id);
        }

        @Override
        public String timedPerAttempt(String id) {
            if (calls.incrementAndGet() < 3) {
                clock.advance(Duration.ofSeconds(5)); // ignores the interrupt; times out
            }
            return "ok " + calls.get();
        }

        @Override
        public String slotDuringBackoff(String id) {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("first attempt fails");
            }
            return "ok";
        }
    };

    @Test
    void rateLimiterRejectionsCountAsBreakerFailures() {
        LimitedApi api = Resilient.of(LimitedApi.class, backend, registry);
        api.limited("a");
        try {
            api.limited("b");
        } catch (RuntimeException expected) {
            assertThat(expected).isInstanceOf(dev.resilience4cata.RateLimitExceededException.class);
        }
        assertThat(registry.circuitBreaker("limited").state()).isEqualTo(CircuitBreakerState.OPEN);
    }

    @Test
    void rejectionsAreRetriedByDefault() {
        LimitedApi api = Resilient.of(LimitedApi.class, backend, registry);
        api.retriedRejections("a");
        assertThat(api.retriedRejections("b")).isEqualTo("ok"); // rejected, retried 1 s later, admitted
        assertThat(calls).hasValue(2);
        assertThat(clock.elapsed()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void eachAttemptHasItsOwnTimeout() {
        LimitedApi api = Resilient.of(LimitedApi.class, backend, registry);
        assertThat(api.timedPerAttempt("a")).isEqualTo("ok 3");
    }

    @Test
    void theBulkheadSlotIsFreeDuringTheBackoff() {
        LimitedApi api = Resilient.of(LimitedApi.class, backend, registry);
        AtomicInteger seen = new AtomicInteger(-1);
        clock.onAdvance(now -> seen.compareAndSet(-1, registry.bulkhead("slots").concurrentCalls()));
        assertThat(api.slotDuringBackoff("a")).isEqualTo("ok");
        assertThat(seen).hasValue(0);
    }
}
