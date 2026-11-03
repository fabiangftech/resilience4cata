package dev.resilience4cata.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.quarkus.deployment.unprocessed.Unprocessed;
import dev.resilience4cata.quarkus.deployment.unprocessed.UnprocessedPrices;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** {@code quarkus.resilience4cata.allow-missing-processor=true}: the application builds without the policies. */
class MissingProcessorAllowedTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> Unprocessed.add(jar, UnprocessedPrices.class))
            .overrideConfigKey("quarkus.resilience4cata.allow-missing-processor", "true");

    @Inject
    UnprocessedPrices prices;

    @Test
    void theBeanIsThePlainClass() {
        assertThat(prices.price("sku-1")).isEqualTo("9.99");
        assertThat(prices.getClass().getName()).doesNotContain("$Resilient");
    }
}
