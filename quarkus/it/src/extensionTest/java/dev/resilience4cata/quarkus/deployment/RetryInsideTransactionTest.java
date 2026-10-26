package dev.resilience4cata.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import dev.resilience4cata.quarkus.deployment.fixtures.LedgerService;
import dev.resilience4cata.quarkus.deployment.fixtures.LedgerService$Resilient;
import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** R4C-QK-002: a retried {@code @Transactional} method of an annotated class fails the build; NOT_SUPPORTED does not count. */
class RetryInsideTransactionTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(LedgerService.class, LedgerService$Resilient.class))
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("[R4C-QK-002]", "@Retry on post (REQUIRED)").doesNotContain("on outside"));

    @Test
    void theBuildFails() {
        fail("the build should have failed");
    }
}
