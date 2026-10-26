package dev.resilience4cata.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import io.quarkus.test.QuarkusExtensionTest;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** A bad key stops the startup with every problem, and the file and line where each key was set. */
class ConfigurationErrorTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addAsResource(new StringAsset("""
                    quarkus.log.level=INFO
                    resilience4cata.defaults.retry.maxAtempts=3
                    resilience4cata.defaults.timeout.value=soon
                    """), "application.properties"))
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("[R4C-CFG-001]", "'resilience4cata.defaults.retry.maxAtempts' (application.properties:2)",
                            "Did you mean", "application.properties:3"));

    @Test
    void theStartupFails() {
        fail("the startup should have failed");
    }
}
