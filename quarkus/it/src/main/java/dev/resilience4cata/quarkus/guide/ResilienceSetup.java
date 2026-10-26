package dev.resilience4cata.quarkus.guide;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.quarkus.ResilienceRegistryCustomizer;
import java.time.Duration;
import jakarta.inject.Singleton;

// tag::quarkus-customizer[]
@Singleton
public class ResilienceSetup implements ResilienceRegistryCustomizer {

    @Override
    public void customize(ResilienceRegistry.Builder builder) {
        builder.retry("slow", retry -> retry.maxAttempts(5).delay(Duration.ofSeconds(1)));
    }
}
// end::quarkus-customizer[]
