package dev.resilience4cata.quarkus.usability;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.quarkus.usability.fixtures.Flaky;
import dev.resilience4cata.quarkus.usability.fixtures.MutinyQuotes;
import dev.resilience4cata.quarkus.usability.fixtures.MutinyQuotes$Resilient;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * A {@code @Retry} on a method returning Mutiny's {@code Uni}, the default reactive type of Quarkus. The processor
 * rejects other asynchronous types it does not support (R4C-ASY-001), so a user expects either a retry or a
 * compile error.
 */
class MutinyReturnTypeTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Flaky.class, MutinyQuotes.class, MutinyQuotes$Resilient.class));

    @Inject
    MutinyQuotes quotes;

    @Test
    void theBeanIsTheGeneratedSubclass() {
        assertThat(quotes.quote("x")).isNotNull();
        assertThat(quotes.calls()).as("nothing runs before the Uni is subscribed").isZero();
    }

    @Test
    @Disabled("BUG-QK-5: a Uni-returning method compiles as synchronous: the policies wrap the Uni's creation, not its"
            + " execution, so @Retry/@Timeout/@CircuitBreaker silently do nothing")
    void aFailingUniIsRetried() {
        quotes.failNext(2);
        int before = quotes.calls();
        String quote = quotes.quote("r4c").await().indefinitely();
        assertThat(quote).isEqualTo("r4c=42");
        assertThat(quotes.calls() - before).isEqualTo(3);
    }
}
