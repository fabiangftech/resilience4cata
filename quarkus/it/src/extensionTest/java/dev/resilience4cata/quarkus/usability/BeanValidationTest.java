package dev.resilience4cata.quarkus.usability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import dev.resilience4cata.quarkus.usability.fixtures.Accounts;
import dev.resilience4cata.quarkus.usability.fixtures.Accounts$Resilient;
import dev.resilience4cata.quarkus.usability.fixtures.Flaky;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Hibernate Validator's method validation on an annotated class. The generated override repeats the parameter and
 * return value constraints, which Bean Validation accepts for parameters only when they are the same as the overridden
 * method's. {@code @Valid} on a return value: {@link CascadedReturnValueTest}.
 */
class BeanValidationTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Flaky.class, Accounts.class, Accounts.Account.class,
                    Accounts$Resilient.class));

    @Inject
    Accounts accounts;

    @Test
    void aValidCallIsRetried() {
        accounts.failNext(1);
        int before = accounts.calls();
        assertThat(accounts.open("ana")).isEqualTo("account of ana");
        assertThat(accounts.find("ana").owner()).isEqualTo("ana");
        assertThat(accounts.calls() - before).isEqualTo(3);
    }

    @Test
    void anInvalidArgumentIsRejectedBeforeThePolicies() {
        int before = accounts.calls();
        assertThatExceptionOfType(ConstraintViolationException.class).isThrownBy(() -> accounts.open(" "));
        assertThatExceptionOfType(ConstraintViolationException.class).isThrownBy(() -> accounts.find(null));
        assertThat(accounts.calls()).as("validation runs outside the retry").isEqualTo(before);
    }

    @Test
    @Disabled("BUG-QK-9: the generated override names its parameters p0..pn, so one blank argument gives two violations, open.p0 and open.owner")
    void aViolationNamesTheParameterAsDeclared() {
        assertThatExceptionOfType(ConstraintViolationException.class).isThrownBy(() -> accounts.open(" "))
                .satisfies(e -> assertThat(e.getConstraintViolations())
                        .extracting(v -> v.getPropertyPath().toString()).containsExactly("open.owner"));
    }
}
