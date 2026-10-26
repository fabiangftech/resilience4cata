package dev.resilience4cata.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.quarkus.deployment.fixtures.LedgerService;
import dev.resilience4cata.quarkus.deployment.fixtures.LedgerService$Resilient;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** allow-retry-inside-transaction=true accepts one transaction around every attempt. */
class TransactionAllowedTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(LedgerService.class, LedgerService$Resilient.class))
            .overrideConfigKey("quarkus.resilience4cata.allow-retry-inside-transaction", "true");

    @Inject
    LedgerService ledger;

    @Test
    void theApplicationStarts() {
        assertThat(ledger.post("e")).isEqualTo("e");
    }
}
