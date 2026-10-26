package dev.resilience4cata.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import dev.resilience4cata.quarkus.deployment.fixtures.Tracked;
import dev.resilience4cata.quarkus.deployment.fixtures.Tracked$Resilient;
import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** With verify-instances=fail, an instances key that matches no instance stops the startup (R4C-CFG-013). */
class VerifyInstancesTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Tracked.class, Tracked$Resilient.class))
            .overrideConfigKey("quarkus.resilience4cata.verify-instances", "fail")
            .overrideConfigKey("resilience4cata.instances.trackd.circuitBreaker.minimumCalls", "5")
            .assertException(failure -> assertThat(Failures.messages(failure)).contains("[R4C-CFG-013]", "trackd"));

    @Test
    void theStartupFails() {
        fail("the startup should have failed");
    }
}
