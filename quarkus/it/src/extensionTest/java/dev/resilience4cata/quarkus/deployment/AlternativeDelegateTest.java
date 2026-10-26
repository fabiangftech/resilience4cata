package dev.resilience4cata.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.quarkus.deployment.fixtures.CountingInventory;
import dev.resilience4cata.quarkus.deployment.fixtures.Inventory;
import dev.resilience4cata.quarkus.deployment.fixtures.Inventory$Resilient;
import dev.resilience4cata.quarkus.deployment.fixtures.PromoInventory;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** The wrapper of an enabled alternative outranks the wrapper of the bean it replaces, as the alternative does. */
class AlternativeDelegateTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Inventory.class, Inventory$Resilient.class,
                    CountingInventory.class, PromoInventory.class));

    @Inject
    Inventory inventory;

    @Test
    void theAlternativeIsWrapped() {
        assertThat(inventory).isInstanceOf(Inventory$Resilient.class);
        assertThat(inventory.stock("x")).isEqualTo(-1);
    }
}
