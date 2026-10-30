package dev.resilience4cata.micronaut.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.testkit.ManualClock;
import io.micronaut.context.ApplicationContext;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * An annotated Micronaut bean with {@code Mono} and {@code Flux} methods: Micronaut creates its generated subclass.
 * The registry runs on the context's {@code ManualClock} (fixtures.Clocks), so the retries' delays pass when the
 * test advances it.
 */
class ReactiveBeanTest {

    @Test
    void monoAndFluxMethodsOfABeanAreProtected() {
        try (ApplicationContext context = ApplicationContext.run("reactive")) {
            PriceService service = context.getBean(PriceService.class);
            PriceBackend backend = context.getBean(PriceBackend.class);
            ManualClock clock = context.getBean(ManualClock.class);
            assertThat(service.getClass().getName()).endsWith("PriceService$Resilient");

            backend.failures = 2;
            CompletableFuture<Integer> price = service.price("a").toFuture();
            clock.advanceUntilIdle();
            assertThat(price.join()).as("retried twice").isEqualTo(42);
            assertThat(backend.calls).hasValue(3);

            backend.failures = 2;
            CompletableFuture<List<Integer>> ticks = service.ticks("a").collectList().toFuture();
            clock.advanceUntilIdle();
            assertThat(ticks.join()).containsExactly(41, 42, 43);
            assertThat(backend.calls).hasValue(6);

            backend.failures = 10;
            CompletableFuture<List<Integer>> none = service.ticks("a").collectList().toFuture();
            clock.advanceUntilIdle();
            assertThat(none.join()).as("the fallback's stream").isEmpty();
        }
    }
}
