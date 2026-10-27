package dev.resilience4cata.quarkus.usability;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.quarkus.usability.fixtures.Audit;
import dev.resilience4cata.quarkus.usability.fixtures.Audit$Resilient;
import dev.resilience4cata.quarkus.usability.fixtures.Billing;
import dev.resilience4cata.quarkus.usability.fixtures.Billing$Resilient;
import dev.resilience4cata.quarkus.usability.fixtures.Counted;
import dev.resilience4cata.quarkus.usability.fixtures.CountedInterceptor;
import dev.resilience4cata.quarkus.usability.fixtures.CountedSmsNotifier;
import dev.resilience4cata.quarkus.usability.fixtures.EmailNotifier;
import dev.resilience4cata.quarkus.usability.fixtures.Flaky;
import dev.resilience4cata.quarkus.usability.fixtures.Notifier;
import dev.resilience4cata.quarkus.usability.fixtures.Notifier$Resilient;
import dev.resilience4cata.quarkus.usability.fixtures.Premium;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Observer methods and the application's own interceptors on protected beans, and a custom qualifier on an
 * implementation of an annotated interface. As the guide says, an interceptor runs outside the policies of an
 * annotated class (once per call) and inside the wrapper of an interface (once per attempt).
 */
class ObserverAndInterceptorTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Flaky.class, Audit.class, Audit.Ping.class, Audit$Resilient.class,
                    Counted.class, CountedInterceptor.class, Billing.class, Billing$Resilient.class, Premium.class,
                    Notifier.class, Notifier$Resilient.class, EmailNotifier.class, CountedSmsNotifier.class));

    @Inject
    Event<Audit.Ping> pings;

    @Inject
    Audit audit;

    @Inject
    Billing billing;

    @Inject
    @Premium
    Notifier premium;

    @Inject
    Notifier standard;

    @Inject
    @Premium
    CountedSmsNotifier premiumBean;

    @Test
    void anObserverIsNotifiedOnceAndRetried() {
        assertThat(audit).isInstanceOf(Audit$Resilient.class);
        int before = audit.calls();
        pings.fire(new Audit.Ping("a"));
        assertThat(audit.calls() - before).as("one observer").isEqualTo(1);
        audit.failNext(2);
        pings.fire(new Audit.Ping("b"));
        assertThat(audit.calls() - before).as("retried").isEqualTo(4);
    }

    @Test
    void anInterceptorOfAnAnnotatedClassRunsOncePerCall() {
        billing.failNext(2);
        int before = CountedInterceptor.INVOCATIONS.get();
        int calls = billing.calls();
        assertThat(billing.bill("o1")).isEqualTo("billed o1");
        assertThat(billing.calls() - calls).isEqualTo(3);
        assertThat(CountedInterceptor.INVOCATIONS.get() - before).isEqualTo(1);
    }

    @Test
    void aQualifiedImplementationGetsItsOwnWrapperAndItsInterceptorRunsPerAttempt() {
        assertThat(ClientProxy.unwrap(premium)).isInstanceOf(Notifier$Resilient.class);
        assertThat(ClientProxy.unwrap(standard)).isInstanceOf(Notifier$Resilient.class);
        assertThat(standard.send("x")).isEqualTo("email: x");
        premiumBean.failNext(1);
        int before = CountedInterceptor.INVOCATIONS.get();
        assertThat(premium.send("x")).isEqualTo("premium sms: x");
        assertThat(CountedInterceptor.INVOCATIONS.get() - before).isEqualTo(2);
    }
}
