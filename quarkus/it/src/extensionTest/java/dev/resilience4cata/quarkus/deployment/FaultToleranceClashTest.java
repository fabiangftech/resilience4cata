package dev.resilience4cata.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import dev.resilience4cata.quarkus.deployment.fixtures.MixedClient;
import dev.resilience4cata.quarkus.deployment.fixtures.MixedClient$Resilient;
import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** R4C-QK-001: MicroProfile Fault Tolerance and resilience4cata annotations on one method fail the build. */
class FaultToleranceClashTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(MixedClient.class, MixedClient$Resilient.class))
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("[R4C-QK-001]", "MixedClient.fetch()", "SmallRye Fault Tolerance would apply"));

    @Test
    void theBuildFails() {
        fail("the build should have failed");
    }
}
