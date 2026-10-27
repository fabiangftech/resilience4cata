package dev.resilience4cata.micronaut.usability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.ResilienceRegistry;
import io.micronaut.context.ApplicationContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Configuration the way a Micronaut application writes it. */
class UsabilityConfigurationTest {

    @Test
    void eagerSingletonsDoNotRejectTheInstanceKeysOfPrototypeBeans() {
        try (ApplicationContext context = ApplicationContext.builder()
                .properties(Map.of("test.usability", "scopes",
                        "resilience4cata.instances.prototype-quotes.circuit-breaker.minimum-calls", "5"))
                .eagerInitSingletons(true).start()) {
            context.getBean(PrototypeQuotes.class);
            assertThat(context.getBean(ResilienceRegistry.class).circuitBreaker("prototype-quotes").config().minimumCalls())
                    .isEqualTo(5);
        }
    }

    @Test
    void instanceKeysOfPrototypeBeansApplyWhenTheBeanIsCreated() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("test.usability", "scopes",
                "resilience4cata.instances.prototype-quotes.circuit-breaker.minimum-calls", "5"))) {
            context.getBean(PrototypeQuotes.class);
            assertThat(context.getBean(ResilienceRegistry.class).circuitBreaker("prototype-quotes").config().minimumCalls())
                    .isEqualTo(5);
        }
    }

    @Test
    void anInvalidSwitchValueIsReportedWithAnErrorCode() {
        assertThatThrownBy(() -> ApplicationContext.run(Map.of("resilience4cata.micronaut.verify-instances", "strict")))
                .satisfies(e -> assertThat(messages(e)).contains("R4C-MN-", "verify-instances", "strict"));
    }

    @Test
    void eachContextHasItsOwnRegistryAndBreakerState() {
        try (ApplicationContext first = ApplicationContext.run(Map.of("test.usability", "scopes"));
                ApplicationContext second = ApplicationContext.run(Map.of("test.usability", "scopes"))) {
            first.getBean(Failures.class).failNext(2);
            PrototypeQuotes quotes = first.getBean(PrototypeQuotes.class);
            assertThatThrownBy(() -> quotes.quote("a")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> quotes.quote("a")).isInstanceOf(IllegalStateException.class);
            assertThat(first.getBean(ResilienceRegistry.class).circuitBreaker("prototype-quotes").state().name())
                    .isEqualTo("OPEN");

            assertThat(second.getBean(ResilienceRegistry.class)).isNotSameAs(first.getBean(ResilienceRegistry.class));
            assertThat(second.getBean(PrototypeQuotes.class).quote("b")).isEqualTo("b");
        }
    }

    /** Every message in the cause chain: the code may be on any of them. */
    private static String messages(Throwable thrown) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            all.append(t.getMessage()).append('\n');
        }
        return all.toString();
    }
}
