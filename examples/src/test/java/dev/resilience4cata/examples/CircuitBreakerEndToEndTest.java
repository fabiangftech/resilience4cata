package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.CircuitBreakerHandle;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CircuitBreakerEndToEndTest {

    private final ResilienceRegistry registry = ResilienceRegistry.builder()
            .circuitBreaker("pricing", cb -> cb.windowSize(2).minimumCalls(2).waitInOpen(java.time.Duration.ofHours(1)))
            .build();

    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicBoolean failing = new AtomicBoolean(true);

    private final PricingClient pricing = Resilient.of(PricingClient.class, new PricingClient() {
        @Override
        public int price(String sku) {
            return call();
        }

        @Override
        public int priceWithRetry(String sku) {
            return call();
        }

        @Override
        public void refresh() {
            call();
        }

        @Override
        public int first() {
            return call();
        }

        @Override
        public int second() {
            return call();
        }

        @Override
        public int recent() {
            return call();
        }

        private int call() {
            calls.incrementAndGet();
            if (failing.get()) {
                throw new IllegalStateException("pricing service down");
            }
            return 10;
        }
    }, registry);

    @Test
    void opensAfterTooManyFailuresAndRejectsWithoutCallingTheService() {
        for (int i = 0; i < 4; i++) {
            assertThatIllegalStateException().isThrownBy(() -> pricing.price("sku"));
        }
        assertThat(calls).hasValue(4);

        assertThatThrownBy(() -> pricing.price("sku"))
                .isInstanceOf(CallNotPermittedException.class)
                .hasMessageContaining("PricingClient#price(java.lang.String)");
        assertThat(calls).hasValue(4);
    }

    @Test
    void anOpenBreakerStopsTheRetries() {
        assertThatThrownBy(() -> pricing.priceWithRetry("sku")).isInstanceOf(CallNotPermittedException.class);
        assertThat(calls).as("3 attempts open the breaker; the 4th attempt is rejected").hasValue(3);
    }

    @Test
    void recoversThroughHalfOpen() {
        assertThatIllegalStateException().isThrownBy(pricing::refresh);
        assertThatIllegalStateException().isThrownBy(pricing::refresh);
        // waitInOpen = "0": the next call is a probe
        failing.set(false);
        pricing.refresh();
        pricing.refresh();
        assertThat(calls).hasValue(4);
    }

    @Test
    void operatorsCanForceOpenDisableAndResetABreaker() {
        failing.set(false);
        CircuitBreakerHandle breaker = registry.circuitBreaker(
                "dev.resilience4cata.examples.PricingClient#price(java.lang.String)");
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.CLOSED);

        breaker.forceOpen();
        assertThatThrownBy(() -> pricing.price("sku")).isInstanceOf(CallNotPermittedException.class);

        breaker.disable();
        failing.set(true);
        for (int i = 0; i < 10; i++) {
            assertThatIllegalStateException().isThrownBy(() -> pricing.price("sku"));
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.DISABLED);

        breaker.reset();
        failing.set(false);
        assertThat(pricing.price("sku")).isEqualTo(10);
        assertThat(registry.circuitBreakers()).extracting(CircuitBreakerHandle::id).contains("shared", breaker.id());
    }

    @Test
    void unknownBreakerIdsListTheKnownOnes() {
        assertThatThrownBy(() -> registry.circuitBreaker("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No circuit breaker 'nope'")
                .hasMessageContaining("shared");
    }

    @Test
    void timeWindowsWork() {
        for (int i = 0; i < 3; i++) {
            assertThatIllegalStateException().isThrownBy(pricing::recent);
        }
        assertThatThrownBy(pricing::recent).isInstanceOf(CallNotPermittedException.class);
    }

    @Test
    void methodsWithTheSameNameShareOneBreaker() {
        assertThatIllegalStateException().isThrownBy(pricing::first);
        assertThatIllegalStateException().isThrownBy(pricing::second);

        assertThatThrownBy(pricing::first).isInstanceOf(CallNotPermittedException.class);
        assertThatThrownBy(pricing::second).isInstanceOf(CallNotPermittedException.class)
                .hasMessageContaining("'shared'");
    }
}
