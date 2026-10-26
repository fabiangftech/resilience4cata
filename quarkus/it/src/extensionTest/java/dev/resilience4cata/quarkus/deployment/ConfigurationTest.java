package dev.resilience4cata.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.quarkus.deployment.fixtures.Tracked;
import dev.resilience4cata.quarkus.deployment.fixtures.Tracked$Resilient;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.time.Duration;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** resilience4cata.* keys come from SmallRye Config: application.properties, Quarkus profiles, expressions, system properties. */
class ConfigurationTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Tracked.class, Tracked$Resilient.class)
                    .addAsResource(new StringAsset("""
                            backend.delay=75ms
                            resilience4cata.defaults.retry.delay=${backend.delay}
                            resilience4cata.defaults.retry.maxAttempts=4
                            %test.resilience4cata.defaults.retry.maxAttempts=6
                            %prod.resilience4cata.defaults.retry.maxAttempts=9
                            resilience4cata.configs.slow.timeout.value=3s
                            r4c.configs.fast.timeout.value=1s
                            resilience4cata.instances.tracked.circuit-breaker.minimum-calls=7
                            resilience4cata.defaults.adaptive-limit.algorithm=aimd
                            resilience4cata.configs.backend.adaptiveLimit.maxLimit=40
                            """), "application.properties"))
            .overrideConfigKey("resilience4cata.configs.slow.timeout.value", "5s");

    @Inject
    ResilienceRegistry registry;

    @Inject
    Tracked tracked;

    @Test
    void theKeysAreRead() {
        assertThat(registry.retryConfig("").delay()).as("an expression").isEqualTo(Duration.ofMillis(75));
        assertThat(registry.retryConfig("").maxAttempts()).as("the test profile").isEqualTo(6);
        assertThat(registry.timeoutConfig("slow").timeout()).as("an override wins over the file").isEqualTo(Duration.ofSeconds(5));
        assertThat(registry.timeoutConfig("fast").timeout()).as("the r4c. prefix").isEqualTo(Duration.ofSeconds(1));
        tracked.call();
        assertThat(registry.circuitBreaker("tracked").config().minimumCalls()).isEqualTo(7);
        assertThat(registry.adaptiveLimitConfig("").algorithm()).isEqualTo(dev.resilience4cata.AdaptiveLimitConfig.Algorithm.AIMD);
        assertThat(registry.adaptiveLimitConfig("backend").maxLimit()).isEqualTo(40);
    }
}
