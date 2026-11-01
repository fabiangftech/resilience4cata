package dev.resilience4cata.reactor.generated;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import dev.resilience4cata.ResilienceRegistry;
import org.junit.jupiter.api.Test;

class ReactorAbiTest {

    static {
        ResilienceRegistry.builder().build(); // generated constructors always have a registry
    }

    @Test
    void codeOfThisLevelRuns() {
        assertThatCode(() -> ReactorAbi.require(2, ReactorAbiTest.class)).doesNotThrowAnyException();
    }

    @Test
    void codeOfANewerProcessorFailsWithAClearMessage() {
        assertThatIllegalStateException().isThrownBy(() -> ReactorAbi.require(3, ReactorAbiTest.class))
                .withMessageStartingWith("[R4C-RT-021] dev.resilience4cata.reactor.generated.ReactorAbiTest was generated"
                        + " by a newer resilience4cata-processor than this resilience4cata-reactor supports")
                .withMessageContaining("resilience4cata-bom");
    }
}
