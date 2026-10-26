package dev.resilience4cata.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import dev.resilience4cata.quarkus.deployment.fixtures.ProducingService;
import dev.resilience4cata.quarkus.deployment.fixtures.ProducingService$Resilient;
import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** R4C-QK-004: an annotated class that declares a producer fails the build. */
class ProducerOnAnnotatedClassTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(ProducingService.class, ProducingService$Resilient.class))
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("[R4C-QK-004]", "ProducingService declares greeting()"));

    @Test
    void theBuildFails() {
        fail("the build should have failed");
    }
}
