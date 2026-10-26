package dev.resilience4cata.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import dev.resilience4cata.quarkus.deployment.fixtures.Inventory;
import dev.resilience4cata.quarkus.deployment.fixtures.Inventory$Resilient;
import dev.resilience4cata.quarkus.deployment.fixtures.Pricing;
import dev.resilience4cata.quarkus.deployment.fixtures.Pricing$Resilient;
import dev.resilience4cata.quarkus.deployment.fixtures.TwoFacedCatalog;
import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** R4C-QK-003: a bean that implements two unrelated annotated interfaces fails the build. */
class SeveralInterfacesTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(TwoFacedCatalog.class, Pricing.class, Inventory.class, Pricing$Resilient.class, Inventory$Resilient.class))
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("[R4C-QK-003]", "TwoFacedCatalog"));

    @Test
    void theBuildFails() {
        fail("the build should have failed");
    }
}
