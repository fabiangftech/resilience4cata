package dev.resilience4cata.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import dev.resilience4cata.quarkus.deployment.fixtures.ReactiveLedgerService;
import dev.resilience4cata.quarkus.deployment.fixtures.ReactiveLedgerService$Resilient;
import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** R4C-QK-002 for retried and hedged {@code Mono} and {@code Flux} methods of an annotated class. */
class ReactiveRetryInsideTransactionTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(ReactiveLedgerService.class, ReactiveLedgerService$Resilient.class))
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("[R4C-QK-002]", "@Retry on post (REQUIRED)", "@Hedge on read (REQUIRED)",
                            "@Retry on entries (REQUIRED)"));

    @Test
    void theBuildFails() {
        fail("the build should have failed");
    }
}
