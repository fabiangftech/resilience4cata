package dev.resilience4cata.quarkus.usability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import dev.resilience4cata.quarkus.deployment.Failures;
import dev.resilience4cata.quarkus.usability.fixtures.BetaNotifier;
import dev.resilience4cata.quarkus.usability.fixtures.Notifier;
import dev.resilience4cata.quarkus.usability.fixtures.Notifier$Resilient;
import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** R4C-QK-006 for a bean implementing an annotated interface: its synthetic wrapper would ignore the condition. */
class InterfaceLookupConditionTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Notifier.class, Notifier$Resilient.class, BetaNotifier.class))
            .overrideConfigKey("beta.enabled", "false")
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("[R4C-QK-006]", BetaNotifier.class.getName() + " (implements " + Notifier.class.getName()));

    @Test
    void theBuildFails() {
        fail("the build should have failed");
    }
}
