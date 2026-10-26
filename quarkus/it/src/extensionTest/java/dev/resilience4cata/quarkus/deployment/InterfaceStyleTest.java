package dev.resilience4cata.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import dev.resilience4cata.quarkus.deployment.fixtures.CountingInventory;
import dev.resilience4cata.quarkus.deployment.fixtures.Inventory;
import dev.resilience4cata.quarkus.deployment.fixtures.Inventory$Resilient;
import dev.resilience4cata.quarkus.deployment.fixtures.ListPricing;
import dev.resilience4cata.quarkus.deployment.fixtures.Pricing;
import dev.resilience4cata.quarkus.deployment.fixtures.Pricing$Resilient;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** A bean that implements an annotated interface is injected, by the interface, through its generated wrapper. */
class InterfaceStyleTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Pricing.class, Pricing$Resilient.class, ListPricing.class,
                    Inventory.class, Inventory$Resilient.class, CountingInventory.class));

    @Inject
    @Named("list")
    Pricing pricing;

    @Inject
    ListPricing raw;

    @Inject
    Inventory first;

    @Inject
    Inventory second;

    @Test
    void theInterfaceGetsTheWrapperWithTheBeansQualifiers() {
        assertThat(pricing).isInstanceOf(Pricing$Resilient.class);
        raw.failNext(1);
        int calls = raw.calls();
        assertThat(pricing.price("x")).isEqualTo(10);
        assertThat(raw.calls() - calls).as("retried").isEqualTo(2);
    }

    @Test
    void theClassGetsTheBeanItself() {
        raw.failNext(1);
        assertThatIllegalStateException().isThrownBy(() -> raw.price("x"));
    }

    @Test
    void aDependentBeanGetsOneWrapperAndOneDelegatePerInjection() {
        assertThat(first).isInstanceOf(Inventory$Resilient.class).isNotSameAs(second);
        assertThat(first.stock("x")).isNotEqualTo(second.stock("x"));
    }
}
