package dev.resilience4cata.quarkus.usability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import dev.resilience4cata.quarkus.deployment.Failures;
import dev.resilience4cata.quarkus.usability.fixtures.Flaky;
import dev.resilience4cata.quarkus.usability.fixtures.OrdersResource;
import dev.resilience4cata.quarkus.usability.fixtures.OrdersResource$Resilient;
import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * R4C-QK-007 for a Quarkus REST resource without a scope annotation, the form of most Quarkus guides (Quarkus makes it
 * a {@code @Singleton} bean by itself).
 */
class UnscopedRestResourceTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Flaky.class, OrdersResource.class, OrdersResource$Resilient.class))
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("[R4C-QK-007]", OrdersResource.class.getName()));

    @Test
    void theBuildFails() {
        fail("the build should have failed");
    }
}
