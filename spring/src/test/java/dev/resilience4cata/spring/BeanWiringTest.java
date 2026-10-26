package dev.resilience4cata.spring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.spring.fixtures.InventoryClient;
import dev.resilience4cata.spring.fixtures.InventoryClientImpl;
import dev.resilience4cata.spring.fixtures.Money;
import dev.resilience4cata.spring.fixtures.PricingService;
import dev.resilience4cata.spring.fixtures.PricingService$Resilient;
import dev.resilience4cata.testkit.ManualClock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** How annotated classes and beans of annotated interfaces become protected beans. */
class BeanWiringTest {

    private final ApplicationContextRunner runner = Runners.runner();

    @Test
    void anAnnotatedClassIsInstantiatedAsItsGeneratedSubclassWithItsConstructorAnnotations() {
        runner.withUserConfiguration(PricingService.class).withPropertyValues("pricing.margin=20").run(context -> {
            PricingService pricing = context.getBean(PricingService.class);
            assertThat(pricing).isInstanceOf(PricingService$Resilient.class);
            assertThat(pricing.currency()).as("@Qualifier(\"euro\") on the mirrored parameter").isEqualTo("EUR");

            pricing.failures.set(2);
            assertThat(pricing.price("a")).as("@Value on the mirrored parameter").isEqualTo(120);
            assertThat(pricing.attempts).hasValue(3);
            // Exponential backoff with full jitter: up to 50 ms, then up to 100 ms.
            assertThat(context.getBean(ManualClock.class).elapsed()).as("two jittered pauses on the ManualClock bean")
                    .isPositive().isLessThanOrEqualTo(Duration.ofMillis(150));
        });
    }

    @Test
    void callsOnThisGoThroughThePoliciesOfAnAnnotatedClass() {
        runner.withUserConfiguration(PricingService.class).run(context -> {
            PricingService pricing = context.getBean(PricingService.class);
            pricing.failures.set(1);

            assertThat(pricing.twice("a")).isEqualTo(210);
            assertThat(pricing.attempts).as("the first price() failed once and was retried").hasValue(3);
        });
    }

    @Test
    void aBeanOfAnAnnotatedInterfaceIsWrappedAndKeepsItsName() {
        runner.withUserConfiguration(InventoryClientImpl.class).run(context -> {
            InventoryClient inventory = context.getBean(InventoryClient.class);
            assertThat(inventory.getClass().getName()).isEqualTo(InventoryClient.class.getName() + "$Resilient");
            assertThat(context.getBeanNamesForType(InventoryClient.class)).containsExactly("inventoryClientImpl");
            assertThat(context.getBeansOfType(InventoryClientImpl.class)).as("the bean is the wrapper now").isEmpty();
        });
    }

    @Test
    void theWrapperRetriesTheImplementationBean() {
        runner.withBean(InventoryClientImpl.class, InventoryClientImpl::new).run(context -> {
            InventoryClient inventory = context.getBean(InventoryClient.class);
            InventoryClientImpl impl = implementation(inventory);
            impl.failures.set(2);

            assertThat(inventory.stock("a")).isEqualTo(7);
            assertThat(impl.calls).hasValue(3);
        });
    }

    @Test
    void beansThatAreAlreadyGeneratedClassesAreLeftAlone() {
        runner.withUserConfiguration(OwnBeans.class).run(context -> {
            assertThat(context).hasNotFailed();
            InventoryClient inventory = context.getBean(InventoryClient.class);
            OwnBeans own = context.getBean(OwnBeans.class);
            own.impl.failures.set(5);
            try {
                inventory.stock("a");
            } catch (IllegalStateException expected) {
                // gave up
            }
            assertThat(own.impl.calls).as("wrapped once: 3 attempts, not 9").hasValue(3);
            assertThat(context.getBean(PricingService.class)).isExactlyInstanceOf(PricingService$Resilient.class);
        });
    }

    @Test
    void excludedBeansAreLeftAlone() {
        runner.withUserConfiguration(InventoryClientImpl.class, PricingService.class)
                .withPropertyValues("resilience4cata.spring.exclude=inventoryClientImpl,pricingService").run(context -> {
                    assertThat(context.getBean(InventoryClient.class)).isExactlyInstanceOf(InventoryClientImpl.class);
                    assertThat(context.getBean(PricingService.class)).isExactlyInstanceOf(PricingService.class);
                });
    }

    @Test
    void wrapFalseKeepsInterfaceBeansButStillInstantiatesSubclasses() {
        runner.withUserConfiguration(InventoryClientImpl.class, PricingService.class)
                .withPropertyValues("resilience4cata.spring.wrap=false").run(context -> {
                    assertThat(context.getBean(InventoryClient.class)).isExactlyInstanceOf(InventoryClientImpl.class);
                    assertThat(context.getBean(PricingService.class)).isInstanceOf(PricingService$Resilient.class);
                });
    }

    /** A class-based proxy, as {@code @Transactional} or {@code @Cacheable} create, over a generated subclass. */
    @Test
    void aClassBasedProxyCanExtendTheGeneratedSubclassAndRunsOutsideItsPolicies() {
        AtomicInteger intercepted = new AtomicInteger();
        runner.withUserConfiguration(PricingService.class)
                .withBean("proxies", BeanPostProcessor.class, () -> new Proxies(intercepted, true)).run(context -> {
                    PricingService pricing = context.getBean(PricingService.class);
                    assertThat(AopUtils.isCglibProxy(pricing)).isTrue();
                    assertThat(AopUtils.getTargetClass(pricing)).isEqualTo(PricingService$Resilient.class);
                    PricingService target = (PricingService) ((org.springframework.aop.framework.Advised) pricing)
                            .getTargetSource().getTarget();
                    target.failures.set(2);

                    assertThat(pricing.price("a")).isEqualTo(105);
                    assertThat(target.attempts).hasValue(3);
                    assertThat(intercepted).as("one interception around all three attempts").hasValue(1);
                });
    }

    /** An interface-based (JDK) proxy, as {@code @Transactional} creates with proxyTargetClass=false. */
    @Test
    void theWrapperOfAnInterfaceGoesAroundAnInterfaceProxy() {
        AtomicInteger intercepted = new AtomicInteger();
        InventoryClientImpl impl = new InventoryClientImpl();
        runner.withBean("inventory", InventoryClientImpl.class, () -> impl)
                .withBean("proxies", BeanPostProcessor.class, () -> new Proxies(intercepted, false)).run(context -> {
                    InventoryClient inventory = context.getBean(InventoryClient.class);
                    assertThat(inventory.getClass().getName()).endsWith("InventoryClient$Resilient");
                    impl.failures.set(2);

                    assertThat(inventory.stock("a")).isEqualTo(7);
                    assertThat(intercepted).as("each attempt goes through the proxy (a new transaction each)")
                            .hasValue(3);
                });
    }

    @Test
    void anotherPostProcessorsProxyOfAnAnnotatedClassBeanIsNotMistakenForAnUnprotectedInstance() {
        runner.withUserConfiguration(PricingService.class)
                .withBean("proxies", BeanPostProcessor.class, () -> new Proxies(new AtomicInteger(), true))
                .run(context -> assertThat(context).hasNotFailed());
    }

    /** The wrapped bean, from the generated wrapper's {@code delegate} field. */
    @Test
    void aScopedBeanOfAnAnnotatedInterfaceIsWrappedOnce() {
        runner.withUserConfiguration(Scoped.class).run(context -> {
            InventoryClient inventory = context.getBean(InventoryClient.class);
            assertThat(inventory.getClass().getName()).endsWith("InventoryClient$Resilient");
            assertThat(inventory.stock("a")).as("creates the target in this thread's scope").isEqualTo(7);
            Scoped.created.get(0).calls.set(0);
            Scoped.created.get(0).failures.set(5);
            try {
                inventory.stock("a");
            } catch (IllegalStateException expected) {
                // gave up
            }
            assertThat(Scoped.created).hasSize(1);
            assertThat(Scoped.created.get(0).calls).as("3 attempts, not 9: the scoped target is not wrapped too")
                    .hasValue(3);
        });
    }

    private static InventoryClientImpl implementation(InventoryClient wrapper) throws ReflectiveOperationException {
        java.lang.reflect.Field delegate = wrapper.getClass().getDeclaredField("delegate");
        delegate.setAccessible(true);
        return (InventoryClientImpl) delegate.get(wrapper);
    }

    /** An interface bean in a custom scope, behind a scoped proxy. */
    @Configuration(proxyBeanMethods = false)
    static class Scoped {

        static final java.util.List<InventoryClientImpl> created = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Bean
        static org.springframework.beans.factory.config.CustomScopeConfigurer threadScope() {
            org.springframework.beans.factory.config.CustomScopeConfigurer scopes =
                    new org.springframework.beans.factory.config.CustomScopeConfigurer();
            scopes.addScope("thread", new org.springframework.context.support.SimpleThreadScope());
            return scopes;
        }

        @Bean
        @org.springframework.context.annotation.Scope(scopeName = "thread",
                proxyMode = org.springframework.context.annotation.ScopedProxyMode.INTERFACES)
        InventoryClient inventory() {
            InventoryClientImpl impl = new InventoryClientImpl();
            created.add(impl);
            return impl;
        }
    }

    /** A {@code @Bean} method that wraps itself, and one that instantiates the generated subclass. */
    @Configuration(proxyBeanMethods = false)
    static class OwnBeans {

        final InventoryClientImpl impl = new InventoryClientImpl();

        @Bean
        InventoryClient inventory(ResilienceRegistry registry) {
            return Resilient.of(InventoryClient.class, impl, registry);
        }

        @Bean
        PricingService pricing(ResilienceRegistry registry) {
            return new PricingService$Resilient(registry, new Money("CHF"), 5);
        }
    }

    /** Proxies the pricing and inventory beans like Spring's auto-proxy creator, before the integration runs. */
    static final class Proxies implements BeanPostProcessor, Ordered {

        private final AtomicInteger intercepted;
        private final boolean targetClass;

        Proxies(AtomicInteger intercepted, boolean targetClass) {
            this.intercepted = intercepted;
            this.targetClass = targetClass;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (!(bean instanceof PricingService) && !(bean instanceof InventoryClient)) {
                return bean;
            }
            ProxyFactory factory = new ProxyFactory(bean);
            factory.setProxyTargetClass(targetClass);
            if (!targetClass) {
                factory.setInterfaces(InventoryClient.class);
            }
            factory.addAdvice((MethodInterceptor) invocation -> {
                intercepted.incrementAndGet();
                return invocation.proceed();
            });
            return factory.getProxy();
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }
    }
}
