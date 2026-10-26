package dev.resilience4cata.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.quarkus.deployment.fixtures.Tracked;
import dev.resilience4cata.quarkus.deployment.fixtures.Tracked$Resilient;
import dev.resilience4cata.quarkus.runtime.ResilienceHealthCheck;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Breakers listed in health.down-when-open turn the readiness check DOWN while open; the others never do. */
class HealthCheckTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Tracked.class, Tracked$Resilient.class))
            .overrideRuntimeConfigKey("quarkus.resilience4cata.health.down-when-open", "tracked");

    @Inject
    @Readiness
    ResilienceHealthCheck health;

    @Inject
    ResilienceRegistry registry;

    @Inject
    Tracked tracked;

    @Test
    void anOpenCriticalBreakerIsDown() {
        tracked.call();
        assertThat(health.call().getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
        registry.circuitBreaker("tracked").forceOpen();
        try {
            HealthCheckResponse response = health.call();
            assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.DOWN);
            assertThat(response.getData()).hasValueSatisfying(data -> assertThat(data).containsEntry("tracked", "FORCED_OPEN"));
        } finally {
            registry.circuitBreaker("tracked").reset();
        }
    }
}
