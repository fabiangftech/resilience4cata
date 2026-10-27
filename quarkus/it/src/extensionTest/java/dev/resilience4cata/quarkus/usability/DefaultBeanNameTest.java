package dev.resilience4cata.quarkus.usability;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.quarkus.usability.fixtures.DefaultNamedService;
import dev.resilience4cata.quarkus.usability.fixtures.DefaultNamedService$Resilient;
import dev.resilience4cata.quarkus.usability.fixtures.Flaky;
import io.quarkus.arc.Arc;
import io.quarkus.arc.InjectableBean;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.literal.NamedLiteral;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * An annotated class with {@code @Named} and no value. CDI names such a bean after its class
 * ({@code defaultNamedService}); Qute's {@code {inject:defaultNamedService}}, {@code @Inject @Named DefaultNamedService
 * defaultNamedService} and {@code @Named("defaultNamedService")} lookups depend on that name.
 */
class DefaultBeanNameTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Flaky.class, DefaultNamedService.class,
                    DefaultNamedService$Resilient.class));

    @Inject
    DefaultNamedService service; // keeps the bean from being removed as unused

    @Inject
    Instance<Object> any;

    @Test
    void theBeanIsTheGeneratedSubclass() {
        service.failNext(2);
        assertThat(service.hello()).isEqualTo("hello");
        assertThat(service.calls()).isEqualTo(3);
    }

    @Test
    void theDefaultNameIsTheAnnotatedClasssName() {
        InjectableBean<?> bean = Arc.container().instance(DefaultNamedService.class).getBean();
        assertThat(bean.getName()).isEqualTo("defaultNamedService");
        assertThat(any.select(NamedLiteral.of("defaultNamedService")).isResolvable()).isTrue();
    }
}
