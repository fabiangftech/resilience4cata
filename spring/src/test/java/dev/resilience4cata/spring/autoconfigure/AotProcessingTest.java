package dev.resilience4cata.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.ResilienceSnapshot;
import dev.resilience4cata.spring.ResilienceEndpoint;
import dev.resilience4cata.spring.fixtures.InventoryClient;
import dev.resilience4cata.spring.fixtures.InventoryClientImpl;
import dev.resilience4cata.spring.fixtures.Money;
import dev.resilience4cata.spring.fixtures.PricingService;
import dev.resilience4cata.spring.fixtures.PricingService$Resilient;
import dev.resilience4cata.testkit.ManualClock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.aot.test.generate.TestGenerationContext;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.aot.ApplicationContextAotGenerator;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.test.tools.CompileWithForkedClassLoader;
import org.springframework.core.test.tools.TestCompiler;
import org.springframework.javapoet.ClassName;

/**
 * Spring AOT processing on the JVM, as the build of a native image (or an AOT-optimized JVM application) runs
 * it: the bean definitions are processed ahead of time, the generated initializer is compiled and run, and the
 * protected beans must work without the bean factory post-processor that swapped their classes.
 */
@CompileWithForkedClassLoader
class AotProcessingTest {

    @Test
    void theOptimizedContextInstantiatesTheGeneratedSubclassAndWrapsInterfaceBeans() {
        AnnotationConfigApplicationContext source = new AnnotationConfigApplicationContext();
        source.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                Map.of("management.endpoints.web.exposure.include", "resilience4cata")));
        source.register(App.class);
        TestGenerationContext generation = new TestGenerationContext();
        ClassName initializer = new ApplicationContextAotGenerator().processAheadOfTime(source, generation);
        generation.writeGeneratedContent();

        assertThat(RuntimeHintsPredicates.reflection().onType(ResilienceSnapshot.class))
                .as("the endpoint's return type is registered for JSON").accepts(generation.getRuntimeHints());

        TestCompiler.forSystem().with(generation).compile(compiled -> {
            assertThat(compiled.getSourceFiles().stream().anyMatch(file ->
                    file.getContent().contains("new PricingService$Resilient(")))
                    .as("the instance supplier calls the generated constructor, no reflection").isTrue();

            @SuppressWarnings("unchecked")
            ApplicationContextInitializer<GenericApplicationContext> init =
                    compiled.getInstance(ApplicationContextInitializer.class, initializer.toString());
            try (GenericApplicationContext optimized = new GenericApplicationContext()) {
                optimized.getEnvironment().getPropertySources().addFirst(new MapPropertySource("runtime",
                        Map.of("pricing.margin", "7")));
                init.initialize(optimized);
                optimized.refresh();

                assertThat(optimized.getBeanNamesForType(ResilientClassesPostProcessor.class))
                        .as("left out of the optimized context").isEmpty();
                PricingService pricing = optimized.getBean(PricingService.class);
                assertThat(pricing).isInstanceOf(PricingService$Resilient.class);
                assertThat(pricing.currency()).isEqualTo("EUR");
                pricing.failures.set(2);
                assertThat(pricing.price("a")).as("@Value resolved at run time").isEqualTo(107);
                assertThat(pricing.attempts).hasValue(3);

                InventoryClient inventory = optimized.getBean(InventoryClient.class);
                assertThat(inventory.getClass().getName()).endsWith("InventoryClient$Resilient");
                assertThat(optimized.getBean(ResilienceEndpoint.class).snapshot().retries()).hasSize(2);
            }
        });
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({ResilienceAutoConfiguration.class, ResilienceEndpointAutoConfiguration.class})
    @Import({PricingService.class, InventoryClientImpl.class})
    static class App {

        @Bean
        ManualClock clock() {
            return new ManualClock();
        }

        @Bean
        Money euro() {
            return new Money("EUR");
        }

        @Bean
        Money dollar() {
            return new Money("USD");
        }
    }
}
