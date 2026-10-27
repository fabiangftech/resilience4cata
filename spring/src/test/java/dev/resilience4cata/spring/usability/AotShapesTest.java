package dev.resilience4cata.spring.usability;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.spring.autoconfigure.ResilienceAutoConfiguration;
import dev.resilience4cata.spring.usability.fixtures.LazyPayments;
import dev.resilience4cata.spring.usability.fixtures.ScopedCounter;
import dev.resilience4cata.spring.usability.fixtures.SpecialClient;
import dev.resilience4cata.testkit.ManualClock;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.aot.test.generate.TestGenerationContext;
import org.springframework.beans.factory.config.CustomScopeConfigurer;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.aot.ApplicationContextAotGenerator;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.context.support.SimpleThreadScope;
import org.springframework.core.test.tools.CompileWithForkedClassLoader;
import org.springframework.core.test.tools.TestCompiler;
import org.springframework.javapoet.ClassName;

/** Spring AOT with less common bean shapes: a class-based scoped proxy, a lazy bean, an annotated subclass. */
@CompileWithForkedClassLoader
class AotShapesTest {

    @Test
    void scopedLazyAndInheritedAnnotatedClassesWorkInTheOptimizedContext() {
        AnnotationConfigApplicationContext source = new AnnotationConfigApplicationContext();
        source.register(App.class);
        TestGenerationContext generation = new TestGenerationContext();
        ClassName initializer = new ApplicationContextAotGenerator().processAheadOfTime(source, generation);
        generation.writeGeneratedContent();

        TestCompiler.forSystem().with(generation).compile(compiled -> {
            @SuppressWarnings("unchecked")
            ApplicationContextInitializer<GenericApplicationContext> init =
                    compiled.getInstance(ApplicationContextInitializer.class, initializer.toString());
            try (GenericApplicationContext optimized = new GenericApplicationContext()) {
                init.initialize(optimized);
                optimized.refresh();

                ScopedCounter counter = optimized.getBean(ScopedCounter.class);
                assertThat(AopUtils.isCglibProxy(counter)).isTrue();
                counter.failNext(2);
                assertThat(counter.next()).isEqualTo(3);

                SpecialClient client = optimized.getBean(SpecialClient.class);
                assertThat(client.getClass().getName()).endsWith("SpecialClient$Resilient");
                client.failures.set(1);
                assertThat(client.fetch("1")).isEqualTo("fetched-1");
                assertThat(client.attempts).hasValue(2);

                assertThat(optimized.getBean(LazyPayments.class).getClass().getName())
                        .endsWith("LazyPayments$Resilient");
            }
        });
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(ResilienceAutoConfiguration.class)
    @Import({ScopedCounter.class, SpecialClient.class, LazyPayments.class})
    static class App {

        @Bean
        static CustomScopeConfigurer threadScope() {
            CustomScopeConfigurer scopes = new CustomScopeConfigurer();
            scopes.addScope("thread", new SimpleThreadScope());
            return scopes;
        }

        @Bean
        ManualClock clock() {
            return new ManualClock();
        }
    }
}
