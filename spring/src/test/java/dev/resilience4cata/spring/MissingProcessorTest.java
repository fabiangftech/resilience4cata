package dev.resilience4cata.spring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.spring.unprocessed.WithoutGeneratedClasses;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Beans with resilience4cata annotations but no generated class, as when the annotation processor is missing from the
 * build: the startup fails with R4C-SPR-006 instead of running them unprotected, unless allowed.
 */
class MissingProcessorTest {

    private final WithoutGeneratedClasses loader = new WithoutGeneratedClasses();
    private final ApplicationContextRunner runner = Runners.runner().withClassLoader(loader);

    private Class<?> load(String simpleName) throws ClassNotFoundException {
        return loader.loadClass("dev.resilience4cata.spring.unprocessed." + simpleName);
    }

    @Test
    void anAnnotatedClassWithoutItsGeneratedSubclassFailsTheStartup() throws Exception {
        Class<?> service = load("PriceService");
        assertThat(service.getClassLoader()).isSameAs(loader);

        runner.withBean("prices", Object.class, () -> newInstance(service)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageStartingWith("[R4C-SPR-006] Bean 'prices' is an instance of "
                            + "dev.resilience4cata.spring.unprocessed.PriceService, which has resilience4cata"
                            + " annotations, but there is no generated "
                            + "dev.resilience4cata.spring.unprocessed.PriceService$Resilient")
                    .hasMessageContaining("annotationProcessor 'dev.resilience4cata:resilience4cata-processor'")
                    .hasMessageContaining("resilience4cata.spring.allow-missing-processor=true");
        });
    }

    @Test
    void aBeanOfAnAnnotatedInterfaceWithoutItsWrapperFailsTheStartup() throws Exception {
        Class<?> impl = load("StockApiImpl");

        runner.withBean("stock", Object.class, () -> newInstance(impl)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageStartingWith("[R4C-SPR-006] Bean 'stock' implements "
                            + "dev.resilience4cata.spring.unprocessed.StockApi, which has resilience4cata annotations");
        });
    }

    @Test
    void theCheckCanBeTurnedOffOrABeanExcluded() throws Exception {
        Class<?> service = load("PriceService");

        runner.withPropertyValues("resilience4cata.spring.allow-missing-processor=true")
                .withBean("prices", Object.class, () -> newInstance(service))
                .run(context -> assertThat(context).hasNotFailed().getBean("prices").isInstanceOf(service));
        runner.withPropertyValues("resilience4cata.spring.exclude=prices")
                .withBean("prices", Object.class, () -> newInstance(service))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void aBeanWithoutAnnotationsIsLeftAlone() {
        runner.withBean("plain", StringBuilder.class).run(context -> assertThat(context).hasNotFailed());
    }

    private static Object newInstance(Class<?> type) {
        try {
            return type.getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
