package dev.resilience4cata.quarkus.usability;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.quarkus.usability.fixtures.BetaFeature;
import dev.resilience4cata.quarkus.usability.fixtures.BetaFeature$Resilient;
import dev.resilience4cata.quarkus.usability.fixtures.Clerk;
import dev.resilience4cata.quarkus.usability.fixtures.DefaultTranslator;
import dev.resilience4cata.quarkus.usability.fixtures.DefaultTranslator$Resilient;
import dev.resilience4cata.quarkus.usability.fixtures.Flaky;
import dev.resilience4cata.quarkus.usability.fixtures.Ledger;
import dev.resilience4cata.quarkus.usability.fixtures.Ledger$Resilient;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * ArC features on annotated classes: {@code @DefaultBean}, {@code @LookupIfProperty} (run time), and the guide's fix
 * for R4C-QK-005 (an {@code @Inject} constructor plus a package-private one without parameters).
 */
class ConditionalBeansTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Flaky.class, DefaultTranslator.class,
                    DefaultTranslator$Resilient.class, BetaFeature.class, BetaFeature$Resilient.class, Ledger.class,
                    Ledger$Resilient.class, Clerk.class))
            .overrideConfigKey("beta.enabled", "false");

    @Inject
    DefaultTranslator translator;

    @Inject
    Instance<BetaFeature> beta;

    @Inject
    Ledger ledger;

    @Test
    void aDefaultBeanIsTheGeneratedSubclass() {
        assertThat(translator).isInstanceOf(DefaultTranslator$Resilient.class);
        translator.failNext(1);
        assertThat(translator.translate("hola")).isEqualTo("default:hola");
    }

    @Test
    @Disabled("BUG-QK-2: @LookupIfProperty copied to C$Resilient is ignored: the bean stays resolvable")
    void aLookupConditionStillApplies() {
        assertThat(beta.isResolvable()).as("beta.enabled=false").isFalse();
    }

    @Test
    void anApplicationScopedClassWithConstructorInjectionAndANoArgumentConstructorWorks() {
        assertThat(ledger).isInstanceOf(Ledger$Resilient.class);
        ledger.failNext(2);
        int before = ledger.calls();
        assertThat(ledger.post("e1")).isEqualTo("e1 (signed)");
        assertThat(ledger.calls() - before).isEqualTo(3);
    }
}
