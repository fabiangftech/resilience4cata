package dev.resilience4cata.quarkus.usability;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.quarkus.usability.fixtures.Flaky;
import dev.resilience4cata.quarkus.usability.fixtures.Store;
import dev.resilience4cata.quarkus.usability.fixtures.Store$Resilient;
import dev.resilience4cata.quarkus.usability.fixtures.TextStore;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** A generic annotated interface ({@code Store<T>}), injected with its type argument, as CDI users do. */
class GenericInterfaceTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Flaky.class, Store.class, Store$Resilient.class, TextStore.class));

    @Inject
    Store<String> store;

    @Inject
    TextStore raw;

    @Test
    @Disabled("BUG-QK-3: the wrapper's bean types are raw (Store), so @Inject Store<String> gets the unprotected bean")
    void theParameterizedInterfaceGetsTheWrapper() {
        assertThat(store).isInstanceOf(Store$Resilient.class);
        raw.failNext(2);
        int before = raw.calls();
        assertThat(store.load("1")).isEqualTo("text-1");
        assertThat(raw.calls() - before).as("retried").isEqualTo(3);
    }
}
