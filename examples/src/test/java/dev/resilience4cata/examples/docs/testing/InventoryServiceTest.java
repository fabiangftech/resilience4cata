package dev.resilience4cata.examples.docs.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.CircuitBreakerHandle;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.junit.ResilienceTest;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code docs/en/guides/testing.md}: wrapping in {@code @BeforeEach} with the JUnit extension. */
// tag::testing-junit-before-each[]
@ResilienceTest
class InventoryServiceTest {

    private final AtomicBoolean down = new AtomicBoolean(true);
    private InventoryService inventory;

    @BeforeEach
    void wrap(ResilienceRegistry registry) {
        inventory = Resilient.of(InventoryService.class, sku -> {
            if (down.get()) {
                throw new IllegalStateException("inventory down");
            }
            return 7;
        }, registry);
    }

    @Test
    void theBreakerOpensThenLetsAProbeThrough(ManualClock clock, ResilienceRegistry registry) { // the same registry
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> inventory.stock("X")).isInstanceOf(IllegalStateException.class);
        }
        CircuitBreakerHandle breaker = registry.circuitBreaker("inventory");
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.OPEN);

        down.set(false);
        ResilienceTesting.elapseWaitInOpen(breaker, clock);
        assertThat(inventory.stock("X")).isEqualTo(7);
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.CLOSED);
    }

    @Test
    void eachTestStartsWithAClosedBreaker(ResilienceRegistry registry) { // a new registry for each test
        assertThat(registry.circuitBreaker("inventory").state()).isEqualTo(CircuitBreakerState.CLOSED);
    }
}
// end::testing-junit-before-each[]
