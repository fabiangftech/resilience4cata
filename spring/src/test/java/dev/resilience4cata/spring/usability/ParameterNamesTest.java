package dev.resilience4cata.spring.usability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.resilience4cata.spring.ResilienceEndpoint;
import dev.resilience4cata.spring.Runners;
import dev.resilience4cata.spring.usability.fixtures.OrderEvents;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.OperationType;
import org.springframework.boot.actuate.endpoint.invoke.reflect.OperationMethod;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Spring features that read the names of a bean method's parameters (compiled with {@code -parameters}, as
 * Spring Boot's build plugins do). On annotated classes the bean is the generated subclass, whose overrides are
 * the methods Spring inspects. (A {@code @RestController} whose rate-limited handler takes
 * {@code @PathVariable String id} fails the same way: checked by hand with MockMvc, which answered
 * {@code MissingPathVariableException: Required URI template variable 'p0'}; spring-webmvc is not a test
 * dependency because it turns {@code @SpringBootTest} contexts into web contexts.)
 */
class ParameterNamesTest {

    private final ApplicationContextRunner runner = Runners.runner();

    @Test
    void aRetriedEventListenerEvaluatesItsConditionOnTheParameterName() {
        runner.withUserConfiguration(OrderEvents.class).run(context -> {
            OrderEvents events = context.getBean(OrderEvents.class);
            events.failures.set(1);

            context.publishEvent(new OrderEvents.OrderPlaced("1", true));
            context.publishEvent(new OrderEvents.OrderPlaced("2", false));

            assertThat(events.handled).containsExactly("1");
        });
    }

    /**
     * Spring Boot reads the names of an endpoint's {@code @Selector} parameters when it maps the endpoint for
     * the web; a web application exposing {@code resilience4cata} fails to start with "Failed to extract
     * parameter names" otherwise.
     */
    @Test
    void theEndpointsSelectorParameterHasItsName() {
        assertThatCode(() -> new OperationMethod(ResilienceEndpoint.class.getMethod("instance", String.class),
                OperationType.READ).getParameters().get(0).getName()).doesNotThrowAnyException();
    }
}
