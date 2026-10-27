package dev.resilience4cata.quarkus.usability;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.quarkus.usability.fixtures.Flaky;
import dev.resilience4cata.quarkus.usability.fixtures.MutinyQuotes;
import dev.resilience4cata.quarkus.usability.fixtures.MutinyQuotes$Resilient;
import io.quarkus.test.QuarkusExtensionTest;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Mutiny, Quarkus's reactive library. A {@code @Retry} on a method returning {@code Uni} is a compile error
 * (R4C-ASY-002: the policies would only protect building the pipeline); the protected method returns a
 * {@code CompletionStage} instead, and callers that want a {@code Uni} convert it back.
 */
class MutinyReturnTypeTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Flaky.class, MutinyQuotes.class, MutinyQuotes$Resilient.class));

    @Inject
    MutinyQuotes quotes;

    @Test
    void theBeanIsTheGeneratedSubclass() {
        assertThat(quotes).isInstanceOf(MutinyQuotes$Resilient.class);
    }

    @Test
    void aFailingPipelineIsRetriedThroughItsCompletionStage() {
        quotes.failNext(2);
        int before = quotes.calls();
        String quote = Uni.createFrom().completionStage(() -> quotes.quote("r4c")).await().indefinitely();
        assertThat(quote).isEqualTo("r4c=42");
        assertThat(quotes.calls() - before).isEqualTo(3);
    }
}
