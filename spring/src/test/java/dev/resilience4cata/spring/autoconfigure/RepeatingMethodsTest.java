package dev.resilience4cata.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.spring.fixtures.PaymentsGateway$Resilient;
import dev.resilience4cata.spring.fixtures.PricingService$Resilient;
import dev.resilience4cata.spring.tx.GuardedLedger$Resilient;
import dev.resilience4cata.spring.tx.HedgedLedger$Resilient;
import dev.resilience4cata.spring.tx.RetriedLedger$Resilient;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Which overrides of a generated subclass run their call more than once, read from its bytecode. */
class RepeatingMethodsTest {

    @Test
    void methodsWithARetryOrHedgeLayerAreFound() {
        assertThat(names(RepeatingMethods.of(PricingService$Resilient.class))).as("method-level @Retry")
                .containsExactly(Map.entry("price", "@Retry"));
        assertThat(names(RepeatingMethods.of(RetriedLedger$Resilient.class))).as("class-level @Retry")
                .containsExactly(Map.entry("post", "@Retry"));
        assertThat(names(RepeatingMethods.of(HedgedLedger$Resilient.class))).as("@Hedge, async")
                .containsExactly(Map.entry("read", "@Hedge"));
    }

    @Test
    void policiesThatRunTheCallOnceAreNot() {
        assertThat(RepeatingMethods.of(PaymentsGateway$Resilient.class)).as("@CircuitBreaker").isEmpty();
        assertThat(RepeatingMethods.of(GuardedLedger$Resilient.class)).as("@CircuitBreaker and @Timeout").isEmpty();
        assertThat(RepeatingMethods.of(String.class)).as("no class file through a class loader").isEmpty();
    }

    private static Map<String, String> names(Map<Method, String> methods) {
        Map<String, String> names = new TreeMap<>();
        methods.forEach((method, policy) -> names.put(method.getName(), policy));
        return names;
    }
}
