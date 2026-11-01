package dev.resilience4cata.micronaut.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.LockedOverride;
import dev.resilience4cata.ResilienceRegistry;
import io.micronaut.context.ApplicationContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Everyday Micronaut wiring around protected beans: eager beans, qualifiers, shared names, locked annotations. */
class MicronautScenariosTest {

    @Test
    void aContextScopedAnnotatedClassIsTheSubclassAndItsPostConstructIsRetried() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("test.scenarios", "context"))) {
            WarmedCache cache = context.getBean(WarmedCache.class);
            assertThat(cache).isInstanceOf(WarmedCache$Resilient.class);
            assertThat(cache.warmed).isEqualTo("loaded warm-up");
            assertThat(cache.attempts).as("created once, at startup; its warm-up was retried").hasValue(2);
            assertThat(context.getBeansOfType(WarmedCache.class)).hasSize(1);
        }
    }

    @Test
    void annotatedClassesKeepTheirCustomAndNamedQualifiers() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("test.scenarios", "qualifiers"))) {
            Notifications notifications = context.getBean(Notifications.class);
            EmailNotifier email = context.getBean(EmailNotifier.class);
            SmsNotifier sms = context.getBean(SmsNotifier.class);
            assertThat(notifications.email).isSameAs(email).isInstanceOf(EmailNotifier$Resilient.class);
            assertThat(notifications.sms).isSameAs(sms).isInstanceOf(SmsNotifier$Resilient.class);
            assertThat(notifications.all).as("one bean per class, not the class and its subclass")
                    .containsExactlyInAnyOrder(email, sms);

            email.failures.set(2);
            sms.failures.set(1);
            assertThat(notifications.email.send("hi")).isEqualTo("email hi");
            assertThat(notifications.sms.send("hi")).isEqualTo("sms hi");
            assertThat(email.attempts).hasValue(3);
            assertThat(sms.attempts).hasValue(2);
        }
    }

    @Test
    void twoBeansWithTheSameBreakerNameShareItsState() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("test.scenarios", "shared"))) {
            FirstLedger first = context.getBean(FirstLedger.class);
            SecondLedger second = context.getBean(SecondLedger.class);
            first.failing = true;
            for (int i = 0; i < 2; i++) {
                assertThatThrownBy(() -> first.post("x")).isInstanceOf(IllegalStateException.class);
            }

            assertThatThrownBy(() -> second.post("y")).isInstanceOf(CallNotPermittedException.class);
            assertThat(second.calls).hasValue(0);
            assertThat(context.getBean(ResilienceRegistry.class).snapshot().circuitBreakers()).singleElement()
                    .satisfies(breaker -> {
                        assertThat(breaker.id()).isEqualTo("shared-ledger");
                        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.OPEN);
                    });
        }
    }

    @Test
    void propertiesOverrideAnUnlockedAnnotationButNotALockedOne() {
        Map<String, Object> properties = Map.of("test.scenarios", "locked",
                "resilience4cata.instances.locked-client.retry.max-attempts", "5",
                "resilience4cata.instances.open-client.retry.max-attempts", "5");
        try (ApplicationContext context = ApplicationContext.run(properties)) {
            LockedClient client = context.getBean(LockedClient.class);
            assertThatThrownBy(client::locked).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(client::open).isInstanceOf(IllegalStateException.class);

            assertThat(client.lockedAttempts).as("the locked annotation wins").hasValue(2);
            assertThat(client.openAttempts).as("the property wins over the annotation").hasValue(5);
            assertThat(context.getBean(ResilienceRegistry.class).lockedOverrides()).extracting(LockedOverride::id)
                    .containsExactly("locked-client");
        }
    }

    @Test
    void withEagerSingletonsALockedAnnotationsIgnoredKeyFailsTheStartup() {
        assertThatThrownBy(() -> ApplicationContext.builder()
                .properties(Map.of("test.scenarios", "locked",
                        "resilience4cata.instances.locked-client.retry.max-attempts", "5"))
                .eagerInitSingletons(true).start().close())
                .satisfies(e -> assertThat(messages(e)).contains("R4C-CFG-014", "locked-client"));
    }

    private static String messages(Throwable thrown) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            all.append(t.getMessage()).append('\n');
        }
        return all.toString();
    }
}
