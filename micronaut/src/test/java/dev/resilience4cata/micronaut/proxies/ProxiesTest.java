package dev.resilience4cata.micronaut.proxies;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.CallNotPermittedException;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.event.ApplicationEventPublisher;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Protected beans next to Micronaut's own proxies and invocations, on the system clock (environment
 * {@code proxies}). An around advice on an annotated class's method is Micronaut's proxy of the generated subclass,
 * so it sees one invocation per call; on a {@code @ResilientBean}'s implementation it is inside the wrapper, once per
 * attempt. The scheduler and event listeners invoke the generated override. Prototypes share their named policies.
 * A bean that a bean-created listener depends on is still wrapped.
 */
class ProxiesTest {

    private ApplicationContext context;

    @BeforeEach
    void start() {
        context = ApplicationContext.run("proxies");
    }

    @AfterEach
    void stop() {
        context.close();
    }

    private Backend backend() {
        return context.getBean(Backend.class);
    }

    @Test
    void anAdviceOnAnAnnotatedClassSeesOneInvocationPerCall() {
        AuditedLedger ledger = context.getBean(AuditedLedger.class);
        backend().failures.set(2);

        assertThat(ledger.post("a")).isEqualTo("ok a");
        assertThat(backend().calls).as("three attempts").hasValue(3);
        assertThat(context.getBean(Auditor.class).invocations).as("the advice is outside the retry").hasValue(1);
    }

    @Test
    void anAdviceOnAResilientBeansImplementationRunsPerAttempt() {
        AuditedClient client = context.getBean(AuditedClient.class);
        assertThat(client.getClass().getSimpleName()).isEqualTo("AuditedClient$Resilient");
        backend().failures.set(2);

        assertThat(client.fetch("a")).isEqualTo("ok a");
        assertThat(backend().calls).hasValue(3);
        assertThat(context.getBean(Auditor.class).invocations).as("inside the wrapper: once per attempt").hasValue(3);
    }

    @Test
    void aScheduledMethodOfAnAnnotatedClassIsRetried() throws InterruptedException {
        ScheduledSync sync = context.getBean(ScheduledSync.class);
        awaitRuns(sync, 0);
        int runs = sync.runs.get();
        sync.failures.set(2);
        awaitRuns(sync, runs);
        assertThat(sync.failures.get()).as("both failures were consumed by one retried run").isNegative();
    }

    private static void awaitRuns(ScheduledSync sync, int past) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (sync.runs.get() == past && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(sync.runs.get()).as("a run completed").isGreaterThan(past);
    }

    @Test
    @SuppressWarnings("unchecked")
    void anEventListenerOfAnAnnotatedClassIsRetried() {
        OrderEvents events = context.getBean(OrderEvents.class);
        backend().failures.set(2);
        context.getBean(ApplicationEventPublisher.class).publishEvent(new OrderEvents.OrderPlaced("o1"));

        assertThat(events.handled).containsExactly("ok o1");
        assertThat(backend().calls).hasValue(3);
    }

    @Test
    void prototypesAreNewInstancesThatShareTheirNamedPolicy() {
        PrototypeQuotes first = context.getBean(PrototypeQuotes.class);
        PrototypeQuotes second = context.getBean(PrototypeQuotes.class);
        assertThat(first).isNotSameAs(second);
        assertThat(first.getClass().getSimpleName()).isEqualTo("PrototypeQuotes$Resilient");
        backend().failures.set(10);

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> first.quote("a")).isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> second.quote("b")).as("opened through the other instance")
                .isInstanceOf(CallNotPermittedException.class);
    }

    @Test
    void aBeanThatABeanCreatedListenerDependsOnIsStillWrapped() {
        context.getBean(PrototypeQuotes.class); // creates the listener, and the client with it
        EarlyClient held = context.getBean(ClientUsingListener.class).client;
        assertThat(held.getClass().getSimpleName()).as("the bean the listener got").isEqualTo("EarlyClient$Resilient");
        assertThat(context.getBean(EarlyClient.class)).isSameAs(held);

        backend().failures.set(2);
        assertThat(held.fetch("a")).isEqualTo("ok a");
        assertThat(backend().calls).hasValue(3);
    }
}
