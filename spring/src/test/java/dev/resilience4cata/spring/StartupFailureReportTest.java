package dev.resilience4cata.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.spring.fixtures.InventoryClient;
import dev.resilience4cata.spring.fixtures.InventoryClientImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * What a developer sees when {@code SpringApplication.run} fails (Spring Boot's report, with our analyses), and
 * that a successful startup logs no warning about the integration's beans.
 */
@ExtendWith(OutputCaptureExtension.class)
class StartupFailureReportTest {

    @Test
    void anInvalidPropertyIsReportedWithItsFileAndLine(CapturedOutput output) {
        SpringApplication application = new SpringApplication(App.class);
        application.setWebApplicationType(WebApplicationType.NONE);

        assertThatThrownBy(() -> application.run("--spring.config.name=broken-test")).isNotNull();

        assertThat(output).contains("APPLICATION FAILED TO START", "Invalid resilience4cata configuration (2 problems)",
                "[R4C-CFG-001] unknown key 'resilience4cata.defaults.retry.max-atempts' (class path resource"
                        + " [broken-test.yml] - 4:20). Did you mean 'resilience4cata.defaults.retry.max-attempts'?",
                "Fix the resilience4cata.* keys listed above");
    }

    @Test
    void injectingTheImplementationOfAWrappedBeanIsExplained(CapturedOutput output) {
        SpringApplication application = new SpringApplication(App.class, NeedsImplementation.class);
        application.setWebApplicationType(WebApplicationType.NONE);

        assertThatThrownBy(application::run).isNotNull();

        assertThat(output).contains("APPLICATION FAILED TO START", "which implements only the annotated interface "
                + InventoryClient.class.getName(), "Inject " + InventoryClient.class.getName() + " instead");
    }

    /**
     * The post-processors look the registry up when they first need it: created with them, the registry, its
     * configuration, customizers and listeners would miss the post-processors registered after (Spring warns).
     */
    @Test
    void theRegistryAndItsDependenciesGoThroughEveryPostProcessor(CapturedOutput output) {
        SpringApplication application = new SpringApplication(App.class, WithListener.class);
        application.setWebApplicationType(WebApplicationType.NONE);

        try (var context = application.run()) {
            assertThat(context.getBean(InventoryClient.class).getClass().getName()).endsWith("$Resilient");
        }

        assertThat(output).doesNotContain("is not eligible for getting processed by all BeanPostProcessors");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(InventoryClientImpl.class)
    static class App {
    }

    @Configuration(proxyBeanMethods = false)
    static class WithListener {

        @Bean
        dev.resilience4cata.event.EventListener<dev.resilience4cata.event.RetryEvent> retries() {
            return event -> { };
        }

        @Bean
        ResilienceRegistryCustomizer customizer() {
            return builder -> { };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class NeedsImplementation {

        @Bean
        String consumer(InventoryClientImpl inventory) {
            return "uses " + inventory;
        }
    }
}
