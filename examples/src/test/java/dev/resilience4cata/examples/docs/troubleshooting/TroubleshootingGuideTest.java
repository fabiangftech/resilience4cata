package dev.resilience4cata.examples.docs.troubleshooting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.config.ConfigException;
import dev.resilience4cata.config.ResilienceConfig;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** The code of {@code docs/en/guides/troubleshooting.md}. */
class TroubleshootingGuideTest {

    @Test
    void selfInvocationIsNotInterceptedInTheInterfaceStyle() {
        CatalogApi catalog = Resilient.of(CatalogApi.class, new HttpCatalog(), ResilienceRegistry.builder().build());
        assertThatThrownBy(() -> catalog.total(List.of("a"))).hasMessage("flaky"); // no retry inside total

        CatalogApi retried = Resilient.of(CatalogApi.class, new HttpCatalog(), ResilienceRegistry.builder().build());
        assertThat(retried.price("a")).isEqualTo(10); // through the wrapper: retried
    }

    @Test
    void verifyInstancesReportsOverridesThatMatchNothing() {
        Properties properties = new Properties();
        properties.setProperty("instances.paymnets.retry.maxAttempts", "5"); // typo: the instance is "payments"
        assertThatThrownBy(() -> {
            // tag::troubleshooting-verify-instances[]
            ResilienceConfig config = ResilienceConfig.builder().properties("app.properties", properties).build();
            System.out.println(config.describe()); // every effective value and where it comes from
            ResilienceRegistry registry = config.applyTo(ResilienceRegistry.builder()).build();
            Payments payments = Resilient.of(Payments.class, order -> "ok", registry);
            config.verifyInstances(registry); // after every service is wrapped: fails on ids that match nothing
            // end::troubleshooting-verify-instances[]
            assertThat(payments.charge("o-1")).isEqualTo("ok");
        }).isInstanceOf(ConfigException.class).hasMessageContaining("R4C-CFG-013")
                .hasMessageContaining("Did you mean 'payments'?");
    }

    /** One retried method whose instance is called {@code payments}. */
    public interface Payments {
        @dev.resilience4cata.annotation.Retry(name = "payments")
        String charge(String order);
    }
}
