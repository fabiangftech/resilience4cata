package dev.resilience4cata.quarkus.usability;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.quarkus.usability.fixtures.EmailNotifier;
import dev.resilience4cata.quarkus.usability.fixtures.Flaky;
import dev.resilience4cata.quarkus.usability.fixtures.Notifier;
import dev.resilience4cata.quarkus.usability.fixtures.Notifier$Resilient;
import dev.resilience4cata.quarkus.usability.fixtures.SmsNotifier;
import io.quarkus.arc.All;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Several beans of one annotated interface, used as plug-ins: {@code @All List<Notifier>} or
 * {@code Instance<Notifier>} iterated. Each implementation should be called once, through its wrapper.
 */
class AllImplementationsTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Flaky.class, Notifier.class, Notifier$Resilient.class,
                    EmailNotifier.class, SmsNotifier.class));

    @Inject
    @All
    List<Notifier> all;

    @Inject
    Instance<Notifier> instances;

    @Inject
    EmailNotifier email;

    @Inject
    SmsNotifier sms;

    @Test
    void everyImplementationIsListedOnceAndProtected() {
        assertThat(all).hasSize(2).allMatch(Notifier$Resilient.class::isInstance);
        assertThat(instances.stream().toList()).hasSize(2).allMatch(Notifier$Resilient.class::isInstance);
        email.failNext(1);
        sms.failNext(1);
        assertThat(all.stream().map(n -> n.send("hi")).sorted().toList()).containsExactly("email: hi", "sms: hi");
    }
}
