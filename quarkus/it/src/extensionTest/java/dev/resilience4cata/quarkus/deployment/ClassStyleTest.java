package dev.resilience4cata.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.quarkus.deployment.fixtures.Cart;
import dev.resilience4cata.quarkus.deployment.fixtures.Cart$Resilient;
import dev.resilience4cata.quarkus.deployment.fixtures.Excluded;
import dev.resilience4cata.quarkus.deployment.fixtures.Excluded$Resilient;
import dev.resilience4cata.quarkus.deployment.fixtures.Helper;
import dev.resilience4cata.quarkus.deployment.fixtures.NamedGreeter;
import dev.resilience4cata.quarkus.deployment.fixtures.NamedGreeter$Resilient;
import dev.resilience4cata.quarkus.deployment.fixtures.Ticket;
import dev.resilience4cata.quarkus.deployment.fixtures.Ticket$Resilient;
import dev.resilience4cata.quarkus.deployment.fixtures.TwoConstructors;
import dev.resilience4cata.quarkus.deployment.fixtures.TwoConstructors$Resilient;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Annotated classes become their generated subclass, keeping their scope, qualifiers and constructors. */
class ClassStyleTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Helper.class, NamedGreeter.class, NamedGreeter$Resilient.class,
                    Ticket.class, Ticket$Resilient.class, Cart.class, Cart$Resilient.class, TwoConstructors.class,
                    TwoConstructors$Resilient.class, Excluded.class, Excluded$Resilient.class))
            .overrideConfigKey("quarkus.resilience4cata.beans.exclude", Excluded.class.getName());

    @Inject
    @Named("greeter")
    NamedGreeter greeter;

    @Inject
    Ticket first;

    @Inject
    Ticket second;

    @Inject
    TwoConstructors twoConstructors;

    @Inject
    Excluded excluded;

    @Test
    void aSingletonWithAQualifierAndAnInjectConstructorIsTheSubclass() {
        assertThat(greeter).isInstanceOf(NamedGreeter$Resilient.class);
        greeter.failNext(2);
        int calls = greeter.calls();
        assertThat(greeter.greet("ana")).isEqualTo("helper greets ana");
        assertThat(greeter.calls() - calls).isEqualTo(3);
    }

    @Test
    void aDependentClassStaysDependent() {
        assertThat(first).isInstanceOf(Ticket$Resilient.class).isNotSameAs(second);
        assertThat(first.issue()).isEqualTo("helper");
    }

    @Inject
    Cart cart;

    @Test
    void aRequestScopedClassIsProxied() {
        ManagedContext request = Arc.container().requestContext();
        request.activate();
        try {
            assertThat(cart.add("a")).isEqualTo(1);
            assertThat(cart.add("b")).as("same request, same cart").isEqualTo(2);
        } finally {
            request.terminate();
        }
        request.activate();
        try {
            assertThat(cart.add("c")).as("a new request, a new cart").isEqualTo(1);
        } finally {
            request.terminate();
        }
        assertThat(Arc.container().select(Cart$Resilient.class).isResolvable()).isTrue();
    }

    @Test
    void theNoArgumentConstructorIsStillTheOneUsed() {
        assertThat(twoConstructors).isInstanceOf(TwoConstructors$Resilient.class);
        assertThat(twoConstructors.name()).isEqualTo("default");
    }

    @Test
    void anExcludedClassIsLeftAlone() {
        assertThat(excluded.getClass()).isEqualTo(Excluded.class);
    }
}
