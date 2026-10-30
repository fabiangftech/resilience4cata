package dev.resilience4cata.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.quarkus.deployment.proxies.Audit;
import dev.resilience4cata.quarkus.deployment.proxies.AuditedClient;
import dev.resilience4cata.quarkus.deployment.proxies.AuditedClient$Resilient;
import dev.resilience4cata.quarkus.deployment.proxies.AuditedLedger;
import dev.resilience4cata.quarkus.deployment.proxies.AuditedLedger$Resilient;
import dev.resilience4cata.quarkus.deployment.proxies.Auditor;
import dev.resilience4cata.quarkus.deployment.proxies.Backend;
import dev.resilience4cata.quarkus.deployment.proxies.BackendAuditedClient;
import dev.resilience4cata.quarkus.deployment.proxies.BackendEarlyClient;
import dev.resilience4cata.quarkus.deployment.proxies.EarlyClient;
import dev.resilience4cata.quarkus.deployment.proxies.EarlyClient$Resilient;
import dev.resilience4cata.quarkus.deployment.proxies.EarlyStartup;
import dev.resilience4cata.quarkus.deployment.proxies.OrderEvents;
import dev.resilience4cata.quarkus.deployment.proxies.OrderEvents$Resilient;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Protected beans next to ArC's interceptors and events. An interceptor binding on an annotated class's method is
 * ArC's interception of the generated subclass, outside the policies: one invocation per call; on a wrapped
 * interface's implementation it is inside the wrapper, once per attempt. An observer method of an annotated class is
 * retried, and observes each event once. A startup observer that runs before the extension's own gets the wrapper.
 */
class ProxiesTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Backend.class, Audit.class, Auditor.class, AuditedLedger.class,
                    AuditedLedger$Resilient.class, AuditedClient.class, AuditedClient$Resilient.class,
                    BackendAuditedClient.class, OrderEvents.class, OrderEvents$Resilient.class,
                    OrderEvents.OrderPlaced.class, EarlyClient.class, EarlyClient$Resilient.class,
                    BackendEarlyClient.class, EarlyStartup.class));

    @Inject
    Backend backend;

    @Inject
    AuditedLedger ledger;

    @Inject
    AuditedClient client;

    @Inject
    OrderEvents events;

    @Inject
    Event<OrderEvents.OrderPlaced> orders;

    @Inject
    EarlyStartup startup;

    @BeforeEach
    void reset() {
        backend.calls.set(0);
        backend.failures.set(0);
        Auditor.INVOCATIONS.set(0);
    }

    @Test
    void anInterceptorOnAnAnnotatedClassSeesOneInvocationPerCall() {
        backend.failures.set(2);

        assertThat(ledger.post("a")).isEqualTo("ok a");
        assertThat(backend.calls).as("three attempts").hasValue(3);
        assertThat(Auditor.INVOCATIONS).as("the interceptor is outside the retry").hasValue(1);
    }

    @Test
    void anInterceptorOnAWrappedInterfacesImplementationRunsPerAttempt() {
        backend.failures.set(2);

        assertThat(client.fetch("a")).isEqualTo("ok a");
        assertThat(backend.calls).hasValue(3);
        assertThat(Auditor.INVOCATIONS).as("inside the wrapper: once per attempt").hasValue(3);
    }

    @Test
    void anObserverOfAnAnnotatedClassIsRetriedAndObservesOnce() {
        backend.failures.set(2);
        orders.fire(new OrderEvents.OrderPlaced("o1"));

        assertThat(events.handled).containsExactly("ok o1");
        assertThat(backend.calls).hasValue(3);
    }

    @Test
    void aStartupObserverBeforeTheExtensionsOwnGetsTheWrapper() {
        assertThat(startup.result).isEqualTo("ok startup");
        assertThat(startup.callsAtStartup).as("retried at startup").isEqualTo(3);
    }
}
