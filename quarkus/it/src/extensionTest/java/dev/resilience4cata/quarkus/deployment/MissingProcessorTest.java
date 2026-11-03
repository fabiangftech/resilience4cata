package dev.resilience4cata.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import dev.resilience4cata.quarkus.deployment.unprocessed.Unprocessed;
import dev.resilience4cata.quarkus.deployment.unprocessed.UnprocessedPrices;
import dev.resilience4cata.quarkus.deployment.unprocessed.UnprocessedStock;
import dev.resilience4cata.quarkus.deployment.unprocessed.UnprocessedStockBean;
import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * R4C-QK-008: beans with resilience4cata annotations whose generated classes are missing (the application gets the
 * annotated types but not their {@code $Resilient}, as when the processor did not run): the build fails instead of
 * running them unprotected.
 */
class MissingProcessorTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> Unprocessed.add(jar, UnprocessedPrices.class, UnprocessedStock.class,
                    UnprocessedStockBean.class))
            .assertException(failure -> assertThat(Failures.messages(failure)).contains("[R4C-QK-008]",
                    UnprocessedPrices.class.getName() + " (no " + UnprocessedPrices.class.getName() + "$Resilient)",
                    UnprocessedStock.class.getName() + " (no " + UnprocessedStock.class.getName() + "$Resilient)",
                    "quarkus.resilience4cata.allow-missing-processor=true"));

    @Test
    void theBuildFails() {
        fail("the build should have failed");
    }
}
