package dev.resilience4cata.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.quarkus.deployment.fixtures.Tracked;
import dev.resilience4cata.quarkus.deployment.fixtures.Tracked$Resilient;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** An application that produces its own registry replaces the extension's; the generated classes use it. */
class CustomRegistryTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Tracked.class, Tracked$Resilient.class, OwnRegistry.class));

    @Inject
    ResilienceRegistry registry;

    @Inject
    Tracked tracked;

    @Test
    void theApplicationsRegistryIsUsed() {
        tracked.call();
        assertThat(registry).isSameAs(OwnRegistry.REGISTRY);
        assertThat(registry.circuitBreaker("tracked").config().minimumCalls()).isEqualTo(3);
    }

    @Singleton
    public static class OwnRegistry {

        static final ResilienceRegistry REGISTRY = ResilienceRegistry.builder()
                .circuitBreakerDefaults(breaker -> breaker.minimumCalls(3)).build();

        @Produces
        @Singleton
        ResilienceRegistry registry() {
            return REGISTRY;
        }
    }
}
