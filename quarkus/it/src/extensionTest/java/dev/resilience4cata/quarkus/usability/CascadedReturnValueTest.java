package dev.resilience4cata.quarkus.usability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import dev.resilience4cata.quarkus.usability.fixtures.Profiles;
import dev.resilience4cata.quarkus.usability.fixtures.Profiles$Resilient;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * {@code @Valid} on the return value of a retried method of an annotated class, with Hibernate Validator. Bean
 * Validation forbids marking a return value for cascaded validation twice in one hierarchy (HV000131), and the
 * generated override repeats the method's {@code @Valid}: the application does not start.
 */
@Disabled("BUG-QK-7: the generated override copies @Valid from the return value: HV000131 at startup")
class CascadedReturnValueTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Profiles.class, Profiles.Profile.class, Profiles$Resilient.class));

    @Inject
    Profiles profiles;

    @Test
    void theResultIsValidatedInCascade() {
        assertThat(profiles.load("ana").name()).isEqualTo("ana");
        assertThatExceptionOfType(ConstraintViolationException.class).isThrownBy(() -> profiles.load(" "));
    }
}
