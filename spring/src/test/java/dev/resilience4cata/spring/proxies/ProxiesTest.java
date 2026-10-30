package dev.resilience4cata.spring.proxies;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.spring.Runners;
import jakarta.validation.ConstraintViolationException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;

/**
 * Protected beans next to the proxies and idioms of a Spring application. Class-based proxies (an {@code @Aspect},
 * {@code @Validated}) go around an annotated class's generated subclass, so they see one invocation per call, and
 * the policies run inside; around an annotated interface the wrapper is outermost, so the implementation's proxies
 * run per attempt. Spring's own invocations ({@code @Scheduled}, {@code @EventListener}) go through the generated
 * override. Prototypes share their named policies. A bean that a post-processor depends on, which Spring creates
 * before the other post-processors, is still protected.
 */
class ProxiesTest {

    private final ApplicationContextRunner runner = Runners.runner().withBean(Backend.class);

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy
    static class Aspects {
        @Bean
        Auditor auditor() {
            return new Auditor();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Validation {
        @Bean
        static LocalValidatorFactoryBean validator() {
            return new LocalValidatorFactoryBean();
        }

        @Bean
        static MethodValidationPostProcessor methodValidation() {
            return new MethodValidationPostProcessor();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class Scheduling {
    }

    @Test
    void anAspectAroundAnAnnotatedClassSeesOneInvocationPerCall() {
        runner.withUserConfiguration(Aspects.class).withBean(AuditedLedger.class).run(context -> {
            AuditedLedger ledger = context.getBean(AuditedLedger.class);
            assertThat(AopUtils.isAopProxy(ledger)).isTrue();
            context.getBean(Backend.class).failures.set(2);

            assertThat(ledger.post("a")).isEqualTo("ok a");
            assertThat(context.getBean(Backend.class).calls).as("three attempts").hasValue(3);
            assertThat(context.getBean(Auditor.class).invocations).as("the aspect is outside the retry").hasValue(1);
        });
    }

    @Test
    void anAspectOnAWrappedInterfacesImplementationRunsPerAttempt() {
        runner.withUserConfiguration(Aspects.class).withBean(BackendAuditedClient.class).run(context -> {
            AuditedClient client = context.getBean(AuditedClient.class);
            assertThat(client.getClass().getSimpleName()).isEqualTo("AuditedClient$Resilient");
            context.getBean(Backend.class).failures.set(2);

            assertThat(client.fetch("a")).isEqualTo("ok a");
            assertThat(context.getBean(Backend.class).calls).hasValue(3);
            assertThat(context.getBean(Auditor.class).invocations).as("inside the wrapper: once per attempt").hasValue(3);
        });
    }

    @Test
    void methodValidationRejectsAnInvalidArgumentBeforeThePolicies() {
        runner.withUserConfiguration(Validation.class).withBean(ValidatedLookup.class).run(context -> {
            ValidatedLookup lookup = context.getBean(ValidatedLookup.class);
            Backend backend = context.getBean(Backend.class);

            assertThatThrownBy(() -> lookup.find(" ")).isInstanceOf(ConstraintViolationException.class);
            assertThat(backend.calls).as("rejected before any attempt").hasValue(0);

            backend.failures.set(2);
            assertThat(lookup.find("a")).isEqualTo("ok a");
            assertThat(backend.calls).hasValue(3);
        });
    }

    @Test
    void aScheduledMethodOfAnAnnotatedClassIsRetried() {
        runner.withUserConfiguration(Scheduling.class).withBean(ScheduledSync.class)
                .run(context -> {
                    Backend backend = context.getBean(Backend.class);
                    ScheduledSync sync = context.getBean(ScheduledSync.class);
                    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                    while (sync.runs.get() == 0 && System.nanoTime() < deadline) {
                        Thread.sleep(10);
                    }
                    assertThat(sync.runs.get()).as("the scheduler ran it").isPositive();
                    assertThat(backend.calls.get()).isGreaterThanOrEqualTo(sync.runs.get());

                    int runs = sync.runs.get();
                    backend.failures.set(2);
                    deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                    while (sync.runs.get() == runs && System.nanoTime() < deadline) {
                        Thread.sleep(10);
                    }
                    assertThat(sync.runs.get()).as("a run with two failures still completes: retried").isGreaterThan(runs);
                });
    }

    @Test
    void anEventListenerOfAnAnnotatedClassIsRetried() {
        runner.withBean(OrderEvents.class).run(context -> {
            context.getBean(Backend.class).failures.set(2);
            context.publishEvent(new OrderEvents.OrderPlaced("o1"));

            assertThat(context.getBean(OrderEvents.class).handled).containsExactly("ok o1");
            assertThat(context.getBean(Backend.class).calls).hasValue(3);
        });
    }

    @Test
    void prototypesAreNewInstancesThatShareTheirNamedPolicy() {
        runner.withInitializer(context -> ((GenericApplicationContext) context).registerBean(PrototypeQuotes.class,
                definition -> definition.setScope("prototype"))).run(context -> {
            PrototypeQuotes first = context.getBean(PrototypeQuotes.class);
            PrototypeQuotes second = context.getBean(PrototypeQuotes.class);
            assertThat(first).isNotSameAs(second);
            assertThat(first.getClass().getSimpleName()).isEqualTo("PrototypeQuotes$Resilient");
            context.getBean(Backend.class).failures.set(10);

            for (int i = 0; i < 2; i++) {
                assertThatThrownBy(() -> first.quote("a")).isInstanceOf(IllegalStateException.class);
            }
            assertThatThrownBy(() -> second.quote("b")).as("opened through the other instance")
                    .isInstanceOf(CallNotPermittedException.class);
        });
    }

    @Test
    void aWrappedBeanAPostProcessorDependsOnStopsTheStartupInsteadOfLosingItsPolicies() {
        runner.withBean(BackendEarlyClient.class).withBean(ClientUsingPostProcessor.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("[R4C-SPR-005] Bean 'backendEarlyClient'")
                    .hasMessageContaining("needed by 'clientUsingPostProcessor'")
                    .hasMessageContaining("ObjectProvider<EarlyClient>");
        });
    }

    @Test
    void aPostProcessorThatLooksTheBeanUpLazilyGetsTheWrapper() {
        runner.withBean(BackendEarlyClient.class).withBean(LazyClientPostProcessor.class).run(context -> {
            assertThat(context).hasNotFailed();
            EarlyClient client = context.getBean(LazyClientPostProcessor.class).client.getObject();
            assertThat(client.getClass().getSimpleName()).isEqualTo("EarlyClient$Resilient");

            context.getBean(Backend.class).failures.set(2);
            assertThat(client.fetch("a")).isEqualTo("ok a");
            assertThat(context.getBean(Backend.class).calls).hasValue(3);
        });
    }

    @Test
    void anExcludedBeanAPostProcessorDependsOnIsLeftAlone() {
        runner.withBean(BackendEarlyClient.class).withBean(ClientUsingPostProcessor.class)
                .withPropertyValues("resilience4cata.spring.exclude=backendEarlyClient")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
