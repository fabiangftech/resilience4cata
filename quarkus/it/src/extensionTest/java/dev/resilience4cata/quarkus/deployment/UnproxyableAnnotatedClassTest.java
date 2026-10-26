package dev.resilience4cata.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import dev.resilience4cata.quarkus.deployment.fixtures.ConstructorOnlyService;
import dev.resilience4cata.quarkus.deployment.fixtures.ConstructorOnlyService$Resilient;
import dev.resilience4cata.quarkus.deployment.fixtures.Helper;
import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** R4C-QK-005: a normal-scoped annotated class without a no-argument constructor fails the build. */
class UnproxyableAnnotatedClassTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(ConstructorOnlyService.class, ConstructorOnlyService$Resilient.class, Helper.class))
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("[R4C-QK-005]", "ConstructorOnlyService is a normal-scoped bean (@ApplicationScoped)"));

    @Test
    void theBuildFails() {
        fail("the build should have failed");
    }
}
