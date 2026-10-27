package dev.resilience4cata.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import dev.resilience4cata.quarkus.usability.fixtures.Flaky;
import dev.resilience4cata.quarkus.usability.fixtures.ScopedOrdersResource;
import dev.resilience4cata.quarkus.usability.fixtures.ScopedOrdersResource$Resilient;
import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * R4C-QK-007: a Quarkus REST resource that is an annotated class. Quarkus REST creates resource classes itself, so
 * the generated subclass would never serve the endpoints and the policies would silently not run: the build fails.
 */
class RestResourceTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Flaky.class, ScopedOrdersResource.class,
                    ScopedOrdersResource$Resilient.class))
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("[R4C-QK-007]", ScopedOrdersResource.class.getName()));

    @Test
    void theBuildFails() {
        fail("the build should have failed");
    }
}
