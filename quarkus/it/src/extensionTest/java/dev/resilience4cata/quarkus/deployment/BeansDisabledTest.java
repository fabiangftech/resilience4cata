package dev.resilience4cata.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.quarkus.deployment.fixtures.Helper;
import dev.resilience4cata.quarkus.deployment.fixtures.ListPricing;
import dev.resilience4cata.quarkus.deployment.fixtures.NamedGreeter;
import dev.resilience4cata.quarkus.deployment.fixtures.NamedGreeter$Resilient;
import dev.resilience4cata.quarkus.deployment.fixtures.Pricing;
import dev.resilience4cata.quarkus.deployment.fixtures.Pricing$Resilient;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** quarkus.resilience4cata.beans.enabled=false leaves every bean as it is. */
class BeansDisabledTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Helper.class, NamedGreeter.class, NamedGreeter$Resilient.class,
                    Pricing.class, Pricing$Resilient.class, ListPricing.class))
            .overrideConfigKey("quarkus.resilience4cata.beans.enabled", "false");

    @Inject
    @Named("greeter")
    NamedGreeter greeter;

    @Inject
    @Named("list")
    Pricing pricing;

    @Test
    void nothingIsReplaced() {
        assertThat(greeter.getClass()).isEqualTo(NamedGreeter.class);
        assertThat(pricing.getClass().getName()).doesNotContain("$Resilient");
    }
}
