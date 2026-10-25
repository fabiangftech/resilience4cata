package dev.resilience4cata.examples.docs.installation;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import org.junit.jupiter.api.Test;

/** The failure that docs/{en,es}/start/installation.md shows when the processor did not run. */
class InstallationTest {

    @Test
    void aMissingGeneratedClassFailsFastAndShowsTheDependencyToAdd() {
        ResilienceRegistry registry = ResilienceRegistry.builder().build();

        assertThatIllegalStateException()
                .isThrownBy(() -> Resilient.of(NotProcessed.class, () -> "x", registry))
                .withMessageStartingWith("[R4C-RT-003] No generated class " + NotProcessed.class.getName() + "$Resilient found.")
                .withMessageContaining("annotationProcessor 'dev.resilience4cata:resilience4cata-processor:<version>'");
    }
}
