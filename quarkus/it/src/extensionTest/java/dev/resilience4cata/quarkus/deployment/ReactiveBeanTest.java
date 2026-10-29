package dev.resilience4cata.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.quarkus.deployment.fixtures.PriceBackend;
import dev.resilience4cata.quarkus.deployment.fixtures.PriceService;
import dev.resilience4cata.quarkus.deployment.fixtures.PriceService$Resilient;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** An annotated bean with Reactor {@code Mono} and {@code Flux} methods: ArC creates its generated subclass. */
class ReactiveBeanTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(PriceService.class, PriceService$Resilient.class, PriceBackend.class));

    @Inject
    PriceService service;

    @Inject
    PriceBackend backend;

    @Test
    void monoAndFluxMethodsOfABeanAreProtected() {
        backend.failNext(2);
        assertThat(service.price("a").block(WAIT)).as("retried twice").isEqualTo(42);
        assertThat(backend.calls()).isEqualTo(3);

        backend.failNext(2);
        assertThat(service.ticks("a").collectList().block(WAIT)).containsExactly(41, 42, 43);
        assertThat(backend.calls()).isEqualTo(6);

        backend.failNext(10);
        assertThat(service.ticks("a").collectList().block(WAIT)).as("the fallback's stream").isEmpty();
    }
}
