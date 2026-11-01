package dev.resilience4cata.spring.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.LockedOverride;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.spring.Runners;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Everyday Spring wiring around protected beans: qualifiers, lists, providers, lifecycle, conditions, ids. */
class SpringScenariosTest {

    private final ApplicationContextRunner runner = Runners.runner();

    /**
     * The bean definition's class becomes {@code EmailNotifier$Resilient}, which does not repeat the class's
     * annotations: a custom qualifier that is not {@code @Inherited} must still match.
     */
    @Test
    void annotatedClassesKeepTheirCustomAndPlainQualifiers() {
        runner.withUserConfiguration(EmailNotifier.class, SmsNotifier.class, Notifications.class).run(context -> {
            assertThat(context).hasNotFailed();
            Notifications notifications = context.getBean(Notifications.class);
            EmailNotifier email = context.getBean(EmailNotifier.class);
            SmsNotifier sms = context.getBean(SmsNotifier.class);
            assertThat(notifications.email).isSameAs(email).isInstanceOf(EmailNotifier$Resilient.class);
            assertThat(notifications.sms).isSameAs(sms).isInstanceOf(SmsNotifier$Resilient.class);

            email.failures.set(2);
            sms.failures.set(1);
            assertThat(notifications.email.send("hi")).isEqualTo("email hi");
            assertThat(notifications.sms.send("hi")).isEqualTo("sms hi");
            assertThat(email.attempts).hasValue(3);
            assertThat(sms.attempts).hasValue(2);
        });
    }

    @Test
    void primaryQualifiedListMapAndProviderInjectionAllReachTheWrappers() {
        runner.withUserConfiguration(PrimaryQuotes.class, BackupQuotes.class, QuotesUser.class).run(context -> {
            assertThat(context).hasNotFailed();
            QuotesUser user = context.getBean(QuotesUser.class);
            PrimaryQuotes primary = (PrimaryQuotes) unwrap(context.getBean("primaryQuotes"));
            BackupQuotes backup = (BackupQuotes) unwrap(context.getBean("backupQuotes"));

            assertThat(user.primary.getClass().getName()).endsWith("Quotes$Resilient");
            assertThat(user.primary.quote("a")).isEqualTo("primary a");
            assertThat(user.backup.quote("a")).isEqualTo("backup a");
            assertThat(user.all).extracting(q -> q.quote("b")).as("in @Order order, both wrapped")
                    .containsExactly("primary b", "backup b");
            assertThat(user.all).allSatisfy(q -> assertThat(q.getClass().getName()).endsWith("Quotes$Resilient"));
            assertThat(user.byName).containsOnlyKeys("primaryQuotes", "backupQuotes");
            assertThat(user.provider.getObject().quote("c")).as("the provider resolves @Primary").isEqualTo("primary c");
            assertThat(user.provider.orderedStream().map(q -> q.quote("d")).toList())
                    .containsExactly("primary d", "backup d");
            assertThat(context.getBean(Quotes.class)).as("by type: the @Primary wrapper").isSameAs(user.primary);

            primary.failures.set(2);
            backup.failures.set(2);
            assertThat(user.provider.getObject().quote("e")).isEqualTo("primary e");
            assertThat(user.byName.get("backupQuotes").quote("e")).isEqualTo("backup e");
            assertThat(primary.attempts).as("4 calls before, then 3 attempts").hasValue(4 + 3);
            assertThat(backup.attempts).as("3 calls before, then 3 attempts").hasValue(3 + 3);
        });
    }

    /** Initialization callbacks run after the generated constructor has set the policy fields. */
    @Test
    void anInitializationCallbackCallingAProtectedMethodIsRetried() {
        runner.withUserConfiguration(WarmedCache.class).run(context -> {
            assertThat(context).hasNotFailed();
            WarmedCache cache = context.getBean(WarmedCache.class);
            assertThat(cache).isInstanceOf(WarmedCache$Resilient.class);
            assertThat(cache.warmed).isEqualTo("loaded warm-up");
            assertThat(cache.attempts).as("the warm-up failed once and was retried").hasValue(2);
        });
    }

    @Test
    void aConditionalAnnotatedClassIsProtectedOnlyWhenItsConditionHolds() {
        runner.withUserConfiguration(ToggledClient.class).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(ToggledClient.class);
            assertThat(context.getBean(ResilienceRegistry.class).snapshot().retries()).isEmpty();
        });
        runner.withUserConfiguration(ToggledClient.class).withPropertyValues("scenarios.toggled=true").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ToggledClient.class);
            ToggledClient client = context.getBean(ToggledClient.class);
            assertThat(client).isInstanceOf(ToggledClient$Resilient.class);
            assertThat(client.call()).isEqualTo("ok");
            assertThat(client.attempts).hasValue(2);
        });
    }

    @Test
    void twoBeansWithTheSameBreakerNameShareItsState() {
        runner.withUserConfiguration(FirstLedger.class, SecondLedger.class).run(context -> {
            FirstLedger first = context.getBean(FirstLedger.class);
            SecondLedger second = context.getBean(SecondLedger.class);
            first.failing = true;
            for (int i = 0; i < 2; i++) {
                assertThatThrownBy(() -> first.post("x")).isInstanceOf(IllegalStateException.class);
            }

            assertThatThrownBy(() -> second.post("y")).as("the other bean's calls are stopped too")
                    .isInstanceOf(CallNotPermittedException.class);
            assertThat(second.calls).hasValue(0);
            ResilienceRegistry registry = context.getBean(ResilienceRegistry.class);
            assertThat(registry.snapshot().circuitBreakers()).singleElement().satisfies(breaker -> {
                assertThat(breaker.id()).isEqualTo("shared-ledger");
                assertThat(breaker.state()).isEqualTo(CircuitBreakerState.OPEN);
            });
        });
    }

    @Test
    void propertiesOverrideAnUnlockedAnnotationButALockedOneFailsTheStartup() {
        ApplicationContextRunner configured = runner.withUserConfiguration(LockedClient.class).withPropertyValues(
                "resilience4cata.instances.locked-client.retry.max-attempts=5",
                "resilience4cata.instances.open-client.retry.max-attempts=5");

        configured.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("R4C-CFG-014")
                    .hasMessageContaining("locked-client").hasMessageNotContaining("'open-client'");
        });
        configured.withPropertyValues("resilience4cata.spring.verify-instances=warn").run(context -> {
            assertThat(context).hasNotFailed();
            LockedClient client = context.getBean(LockedClient.class);
            assertThatThrownBy(client::locked).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(client::open).isInstanceOf(IllegalStateException.class);

            assertThat(client.lockedAttempts).as("the locked annotation wins").hasValue(2);
            assertThat(client.openAttempts).as("the property wins over the annotation").hasValue(5);
            List<LockedOverride> overrides = context.getBean(ResilienceRegistry.class).lockedOverrides();
            assertThat(overrides).extracting(LockedOverride::id).containsExactly("locked-client");
        });
    }

    private static Object unwrap(Object wrapper) throws ReflectiveOperationException {
        java.lang.reflect.Field delegate = wrapper.getClass().getDeclaredField("delegate");
        delegate.setAccessible(true);
        return delegate.get(wrapper);
    }
}
