package dev.resilience4cata.spring.usability;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.spring.ResilienceHealthIndicator;
import dev.resilience4cata.spring.Runners;
import dev.resilience4cata.spring.autoconfigure.ResilienceAutoConfiguration;
import dev.resilience4cata.spring.usability.fixtures.AgeLookup;
import dev.resilience4cata.spring.usability.fixtures.AsyncMailer;
import dev.resilience4cata.spring.usability.fixtures.CachedQuotes;
import dev.resilience4cata.spring.usability.fixtures.Charges;
import dev.resilience4cata.spring.usability.fixtures.DualConstructorService;
import dev.resilience4cata.spring.usability.fixtures.Lookup;
import dev.resilience4cata.spring.usability.fixtures.LookupConsumer;
import dev.resilience4cata.spring.usability.fixtures.NameLookup;
import dev.resilience4cata.spring.usability.fixtures.ScopedCounter;
import dev.resilience4cata.spring.usability.fixtures.SpecialClient;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Component;
import org.springframework.test.util.AopTestUtils;

/** Annotated beans written with everyday Spring idioms: constructors, generics, scopes, caching, async. */
class SpringIdiomsTest {

    private final ApplicationContextRunner runner = Runners.runner();

    @Disabled("BUG-SPR-1: generated overrides rename parameters to p0, p1..., so SpEL #name keys of @Cacheable resolve to null")
    @Test
    void aCacheableKeyNamingTheParameterWorksOnAnAnnotatedClass() {
        runner.withUserConfiguration(Caching.class, CachedQuotes.class).run(context -> {
            assertThat(context).hasNotFailed();
            CachedQuotes quotes = context.getBean(CachedQuotes.class);

            assertThat(quotes.quote("a")).isEqualTo(101);
            assertThat(quotes.quote("a")).as("the second call is served from the cache").isEqualTo(101);
            assertThat(quotes.calls).hasValue(1);
        });
    }

    @Disabled("BUG-SPR-1: generated overrides rename parameters to p0, p1..., hiding the names Spring reads with -parameters")
    @Test
    void theGeneratedOverrideKeepsTheParameterNamesThatSpringReads() throws NoSuchMethodException {
        String[] names = new DefaultParameterNameDiscoverer().getParameterNames(
                dev.resilience4cata.spring.fixtures.PricingService$Resilient.class.getMethod("price", String.class));

        assertThat(names).containsExactly("sku");
    }

    @Disabled("BUG-SPR-2: with two constructors and no @Autowired, the generated subclass has no no-arg constructor")
    @Test
    void anAnnotatedClassWithANoArgAndAnotherConstructorUsesTheNoArgOneAsSpringDoes() {
        runner.withUserConfiguration(DualConstructorService.class).run(context -> {
            assertThat(context).hasNotFailed();
            DualConstructorService service = context.getBean(DualConstructorService.class);
            service.failures.set(1);

            assertThat(service.currency()).isEqualTo("default");
            assertThat(service.attempts).hasValue(2);
        });
    }

    @Disabled("BUG-SPR-4: the wrapper of a generic interface is raw, so injection by type argument becomes ambiguous")
    @Test
    void genericInterfaceBeansAreInjectedByTheirTypeArgument() {
        runner.withUserConfiguration(NameLookup.class, AgeLookup.class, LookupConsumer.class).run(context -> {
            assertThat(context).hasNotFailed();
            Lookup<String> names = context.getBean(LookupConsumer.class).names;

            assertThat(names.getClass().getName()).endsWith("Lookup$Resilient");
            assertThat(names.find("x")).isEqualTo("name-x");
        });
    }

    /** The control for BUG-SPR-4: Spring alone tells the two implementations apart by their type argument. */
    @Test
    void withoutWrappingGenericImplementationsAreInjectedByTheirTypeArgument() {
        runner.withUserConfiguration(NameLookup.class, AgeLookup.class, LookupConsumer.class)
                .withPropertyValues("resilience4cata.spring.wrap=false").run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(LookupConsumer.class).names.find("x")).isEqualTo("name-x");
                });
    }

    /** {@code @Bean} methods declare the type argument in their return type, which Spring reads instead of the bean's class. */
    @Test
    void genericInterfaceBeansFromBeanMethodsAreInjectedByTheirTypeArgument() {
        runner.withUserConfiguration(GenericBeans.class, LookupConsumer.class).run(context -> {
            assertThat(context).hasNotFailed();
            Lookup<String> names = context.getBean(LookupConsumer.class).names;
            assertThat(names.getClass().getName()).endsWith("Lookup$Resilient");
            assertThat(names.find("x")).isEqualTo("name-x");
        });
    }

    @Test
    void aSubclassOfAnAnnotatedClassThatAddsAPolicyKeepsTheInheritedOne() {
        runner.withUserConfiguration(SpecialClient.class).run(context -> {
            SpecialClient client = context.getBean(SpecialClient.class);
            client.failures.set(2);

            assertThat(client.fetch("1")).isEqualTo("fetched-1");
            assertThat(client.attempts).as("the base class's @Retry applies to the bean").hasValue(3);
            assertThat(client.special("1")).isEqualTo("special-1");
        });
    }

    @Test
    void anAnnotatedClassBehindAClassBasedScopedProxyIsProtectedOnce() {
        runner.withUserConfiguration(ThreadScope.class, ScopedCounter.class).run(context -> {
            assertThat(context).hasNotFailed();
            ScopedCounter counter = context.getBean(ScopedCounter.class);
            assertThat(AopUtils.isCglibProxy(counter)).isTrue();
            counter.failNext(2);

            assertThat(counter.next()).isEqualTo(3);
            assertThat(counter.attempts()).as("3 attempts: retried once, not nested").isEqualTo(3);
        });
    }

    @Test
    void aLazyInjectionPointReachesTheGeneratedSubclass() {
        runner.withUserConfiguration(DualConstructorServiceUser.class, SpecialClient.class).run(context -> {
            assertThat(context).hasNotFailed();
            SpecialClient client = context.getBean(DualConstructorServiceUser.class).client;
            context.getBean(SpecialClient.class).failures.set(1);

            assertThat(client.fetch("1")).isEqualTo("fetched-1");
            assertThat(context.getBean(SpecialClient.class).attempts).hasValue(2);
        });
    }

    /**
     * Without the {@code ManualClock}: on a manual clock even a zero-delay async retry waits for the next
     * {@code advance}, and the call starts on the executor's thread, out of the test's reach.
     */
    @Test
    void asyncAndRetryOnOneMethodRetryOnTheExecutorThread() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ResilienceAutoConfiguration.class))
                .withUserConfiguration(Async.class, AsyncMailer.class).run(context -> {
            assertThat(context).hasNotFailed();
            AsyncMailer mailer = context.getBean(AsyncMailer.class);
            assertThat(AopUtils.isAopProxy(mailer)).as("the @Async proxy goes around the generated subclass").isTrue();
            AsyncMailer target = AopTestUtils.getTargetObject(mailer); // a CGLIB proxy's own fields are null
            target.failures.set(2);

            assertThat(mailer.send("a").get(10, TimeUnit.SECONDS)).isEqualTo("sent to a");
            assertThat(target.attempts).hasValue(3);
        });
    }

    @Disabled("BUG-SPR-3: down-when-open is a comma-separated list, so a derived id with several parameters is split")
    @Test
    void downWhenOpenAcceptsADerivedIdOfAMethodWithSeveralParameters() {
        String id = Charges.class.getName() + "#charge(java.lang.String,int)";
        runner.withUserConfiguration(Charges.class).withPropertyValues("resilience4cata.spring.health.down-when-open=" + id)
                .run(context -> {
                    context.getBean(ResilienceRegistry.class).circuitBreaker(id).forceOpen();

                    assertThat(context.getBean(ResilienceHealthIndicator.class).health().getStatus())
                            .isEqualTo(Status.DOWN);
                });
    }

    @Test
    void downWhenOpenAcceptsADerivedIdWithACommaAsAnIndexedElement() {
        String id = Charges.class.getName() + "#charge(java.lang.String,int)";
        runner.withUserConfiguration(Charges.class)
                .withPropertyValues("resilience4cata.spring.health.down-when-open[0]=" + id).run(context -> {
                    context.getBean(ResilienceRegistry.class).circuitBreaker(id).forceOpen();

                    assertThat(context.getBean(ResilienceHealthIndicator.class).health().getStatus())
                            .isEqualTo(Status.DOWN);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    static class Caching {

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("quotes");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class GenericBeans {

        @Bean
        Lookup<String> names() {
            return new NameLookup();
        }

        @Bean
        Lookup<Integer> ages() {
            return new AgeLookup();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAsync
    static class Async {
    }

    @Configuration(proxyBeanMethods = false)
    static class ThreadScope {

        @Bean
        static org.springframework.beans.factory.config.CustomScopeConfigurer threadScope() {
            org.springframework.beans.factory.config.CustomScopeConfigurer scopes =
                    new org.springframework.beans.factory.config.CustomScopeConfigurer();
            scopes.addScope("thread", new org.springframework.context.support.SimpleThreadScope());
            return scopes;
        }
    }

    @Component
    static class DualConstructorServiceUser {

        final SpecialClient client;

        DualConstructorServiceUser(@Lazy SpecialClient client) {
            this.client = client;
        }
    }
}
