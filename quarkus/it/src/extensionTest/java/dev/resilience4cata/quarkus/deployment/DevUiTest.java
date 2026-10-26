package dev.resilience4cata.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.resilience4cata.quarkus.deployment.fixtures.DevUiService;
import dev.resilience4cata.quarkus.deployment.fixtures.DevUiService$Resilient;
import dev.resilience4cata.quarkus.deployment.fixtures.DevUiWarmup;
import io.quarkus.devui.tests.DevUIJsonRPCTest;
import io.quarkus.test.QuarkusDevModeTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** In dev mode, the Dev UI's JsonRPC service reports the registry's instances and acts on circuit breakers. */
class DevUiTest extends DevUIJsonRPCTest {

    @RegisterExtension
    static final QuarkusDevModeTest TEST = new QuarkusDevModeTest()
            .withApplicationRoot(jar -> jar.addClasses(DevUiService.class, DevUiService$Resilient.class, DevUiWarmup.class));

    DevUiTest() {
        // The Dev UI names an extension's JsonRPC namespace (and its card) after the artifact its build step comes
        // from, minus "-deployment": resilience4cata-quarkus for an application that gets the extension from a
        // repository. Here the deployment module is a project of this build, which the application model knows by
        // its project name, so the namespace is "deployment" and the card does not match the extension's entry
        // (named from quarkus-extension.yaml): the card itself is checked against published jars instead.
        super("deployment");
    }

    @Test
    void reportsTheInstancesAndActsOnBreakers() throws Exception {
        JsonNode snapshot = executeJsonRPCMethod("getSnapshot");
        JsonNode breaker = find(snapshot.get("circuitBreakers"), "devui");
        assertThat(breaker.get("state").asText()).isEqualTo("CLOSED");
        // No rate before minimumCalls: the service returns null, which the Dev UI's JSON writer leaves out.
        assertThat(breaker.path("failureRate").isMissingNode() || breaker.path("failureRate").isNull())
                .as("no failure rate yet in %s", breaker).isTrue();
        JsonNode bulkhead = find(snapshot.get("bulkheads"), "devui-bulkhead");
        assertThat(bulkhead.get("maxConcurrent").asInt()).isEqualTo(3);
        assertThat(bulkhead.get("concurrentCalls").asInt()).isZero();
        assertThat(executeJsonRPCMethod("getInstanceCount").asText()).isEqualTo("2 instances");

        assertThat(executeJsonRPCMethod("forceOpen", Map.of("instance", "devui")).get("state").asText())
                .isEqualTo("FORCED_OPEN");
        assertThat(find(executeJsonRPCMethod("getSnapshot").get("circuitBreakers"), "devui").get("state").asText())
                .isEqualTo("FORCED_OPEN");
        assertThat(executeJsonRPCMethod("disable", Map.of("instance", "devui")).get("state").asText())
                .isEqualTo("DISABLED");
        assertThat(executeJsonRPCMethod("reset", Map.of("instance", "devui")).get("state").asText())
                .isEqualTo("CLOSED");
    }

    private static JsonNode find(JsonNode items, String instance) {
        for (JsonNode item : items) {
            if (item.get("instance").asText().equals(instance)) {
                return item;
            }
        }
        throw new AssertionError("no instance " + instance + " in " + items);
    }
}
