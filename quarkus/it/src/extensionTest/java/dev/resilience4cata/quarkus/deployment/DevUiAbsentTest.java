package dev.resilience4cata.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.quarkus.deployment.fixtures.DevUiService;
import dev.resilience4cata.quarkus.deployment.fixtures.DevUiService$Resilient;
import dev.resilience4cata.quarkus.deployment.fixtures.DevUiWarmup;
import dev.resilience4cata.quarkus.runtime.devui.ResilienceJsonRpcService;
import io.quarkus.arc.Arc;
import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Outside dev mode the Dev UI's JsonRPC service is not a bean: its build step runs only in local development. */
class DevUiAbsentTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(DevUiService.class, DevUiService$Resilient.class, DevUiWarmup.class));

    @Test
    void theJsonRpcServiceIsNotABean() {
        assertThat(Arc.container().instance(ResilienceJsonRpcService.class).isAvailable()).isFalse();
        assertThat(Arc.container().instance(DevUiService.class).get().call()).isEqualTo("ok");
    }
}
