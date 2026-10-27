package dev.resilience4cata.quarkus.usability;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.quarkus.usability.fixtures.Greeting;
import dev.resilience4cata.quarkus.usability.fixtures.ProdGreeting;
import dev.resilience4cata.quarkus.usability.fixtures.ProdGreeting$Resilient;
import dev.resilience4cata.quarkus.usability.fixtures.TestGreeting;
import dev.resilience4cata.quarkus.usability.fixtures.TestGreeting$Resilient;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Two annotated implementations of one interface, one per build profile ({@code @IfBuildProfile("prod")} and
 * {@code @UnlessBuildProfile("prod")}): the usual Quarkus way to swap a real client for a fake one. In the test profile
 * only {@code TestGreeting} should be a bean.
 */
class BuildProfileTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Greeting.class, ProdGreeting.class, ProdGreeting$Resilient.class,
                    TestGreeting.class, TestGreeting$Resilient.class));

    @Inject
    Instance<Greeting> greetings; // not @Inject Greeting: with the bug, that injection point fails the whole build

    @Test
    void onlyTheBeanOfTheActiveBuildProfileExists() {
        List<String> classes = greetings.handlesStream().map(h -> h.getBean().getBeanClass().getSimpleName()).toList();
        assertThat(classes).containsExactly(TestGreeting$Resilient.class.getSimpleName());
        assertThat(greetings.get().greet()).isEqualTo("test");
    }
}
