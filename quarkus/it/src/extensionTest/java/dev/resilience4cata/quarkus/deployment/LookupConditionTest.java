package dev.resilience4cata.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import dev.resilience4cata.quarkus.usability.fixtures.BetaFeature;
import dev.resilience4cata.quarkus.usability.fixtures.BetaFeature$Resilient;
import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * R4C-QK-006: an annotated class with {@code @LookupIfProperty}. Quarkus reads lookup conditions on the original class
 * only, so the generated subclass would stay resolvable whatever the property: the build fails.
 */
class LookupConditionTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(BetaFeature.class, BetaFeature$Resilient.class))
            .overrideConfigKey("beta.enabled", "false")
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("[R4C-QK-006]", BetaFeature.class.getName()));

    @Test
    void theBuildFails() {
        fail("the build should have failed");
    }
}
