package dev.resilience4cata.spring.usability;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.spring.Runners;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Spring profiles, as an application selects them, over {@code usability.yml} and {@code usability-prod.yml}. */
class ProfilesTest {

    private final ApplicationContextRunner runner = Runners.runner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.config.name=usability");

    @Test
    void withoutAProfileOnlyTheDefaultDocumentApplies() {
        runner.run(context -> {
            ResilienceRegistry registry = context.getBean(ResilienceRegistry.class);
            assertThat(registry.retryConfig("").maxAttempts()).isEqualTo(4);
            assertThat(registry.retryConfig("").delay()).isEqualTo(Duration.ofMillis(20));
        });
    }

    @Test
    void aProfileDocumentAndAProfileFileOverrideTheDefaults() {
        runner.withPropertyValues("spring.profiles.active=prod").run(context -> {
            ResilienceRegistry registry = context.getBean(ResilienceRegistry.class);
            assertThat(registry.retryConfig("").maxAttempts()).as("on-profile document").isEqualTo(6);
            assertThat(registry.retryConfig("").delay()).as("usability-prod.yml").isEqualTo(Duration.ofMillis(300));
            assertThat(registry.retryConfig("").retryOn()).containsExactly(IOException.class);
        });
    }

    @Test
    void resilienceProfilesFollowSeveralSpringProfiles() {
        runner.withPropertyValues("spring.profiles.active=prod,eu").run(context -> {
            ResilienceRegistry registry = context.getBean(ResilienceRegistry.class);
            assertThat(registry.timeoutConfig("").timeout()).as("resilience4cata.profiles.eu").isEqualTo(Duration.ofSeconds(7));
            assertThat(registry.retryConfig("").maxAttempts()).isEqualTo(6);
        });
    }
}
