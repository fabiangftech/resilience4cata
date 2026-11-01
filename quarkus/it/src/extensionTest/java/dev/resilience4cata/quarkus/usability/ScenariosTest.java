package dev.resilience4cata.quarkus.usability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.LockedOverride;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.quarkus.usability.fixtures.FirstJournal;
import dev.resilience4cata.quarkus.usability.fixtures.FirstJournal$Resilient;
import dev.resilience4cata.quarkus.usability.fixtures.Flaky;
import dev.resilience4cata.quarkus.usability.fixtures.LockedClient;
import dev.resilience4cata.quarkus.usability.fixtures.LockedClient$Resilient;
import dev.resilience4cata.quarkus.usability.fixtures.SecondJournal;
import dev.resilience4cata.quarkus.usability.fixtures.SecondJournal$Resilient;
import dev.resilience4cata.quarkus.usability.fixtures.Toggle;
import dev.resilience4cata.quarkus.usability.fixtures.ToggleOff;
import dev.resilience4cata.quarkus.usability.fixtures.ToggleOff$Resilient;
import dev.resilience4cata.quarkus.usability.fixtures.ToggleOn;
import dev.resilience4cata.quarkus.usability.fixtures.ToggleOn$Resilient;
import dev.resilience4cata.quarkus.usability.fixtures.WarmedCache;
import dev.resilience4cata.quarkus.usability.fixtures.WarmedCache$Resilient;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Everyday Quarkus wiring around protected beans: a {@code @Startup} annotated class, {@code @IfBuildProperty} and
 * {@code @UnlessBuildProperty} implementations, a breaker name shared by two beans, and properties against a locked
 * annotation.
 */
class ScenariosTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Flaky.class, WarmedCache.class, WarmedCache$Resilient.class,
                    Toggle.class, ToggleOn.class, ToggleOn$Resilient.class, ToggleOff.class, ToggleOff$Resilient.class,
                    FirstJournal.class, FirstJournal$Resilient.class, SecondJournal.class, SecondJournal$Resilient.class,
                    LockedClient.class, LockedClient$Resilient.class))
            .overrideConfigKey("scenarios.toggle", "on")
            .overrideConfigKey("resilience4cata.instances.locked-client.retry.max-attempts", "5")
            .overrideConfigKey("resilience4cata.instances.open-client.retry.max-attempts", "5");

    @Inject
    Instance<WarmedCache> caches;

    @Inject
    Instance<Toggle> toggles;

    @Inject
    ToggleOn toggleOn;

    @Inject
    FirstJournal first;

    @Inject
    SecondJournal second;

    @Inject
    LockedClient locked;

    @Inject
    ResilienceRegistry registry;

    @Test
    void aStartupAnnotatedClassIsCreatedOnceAtStartupAsTheSubclassAndItsPostConstructIsRetried() {
        assertThat(WarmedCache.INITIALIZED).as("before any lookup").hasValue(1);
        assertThat(WarmedCache.initializedAs).isEqualTo(WarmedCache$Resilient.class.getName());
        WarmedCache cache = caches.get();
        assertThat(cache.warmed()).isEqualTo("loaded warm-up");
        assertThat(cache.attempts()).as("the warm-up was retried").isEqualTo(2);
        assertThat(WarmedCache.INITIALIZED).hasValue(1);
    }

    @Test
    void onlyTheImplementationOfTheBuildPropertyIsABeanAndItIsProtected() {
        List<String> classes = toggles.handlesStream().map(h -> h.getBean().getBeanClass().getSimpleName()).toList();
        assertThat(classes).containsExactly(ToggleOn$Resilient.class.getSimpleName());
        assertThat(ClientProxy.unwrap(toggleOn)).isInstanceOf(ToggleOn$Resilient.class);
        toggleOn.failNext(1);
        assertThat(toggles.get().state()).isEqualTo("on");
        assertThat(toggleOn.calls()).isEqualTo(2);
    }

    @Test
    void twoBeansWithTheSameBreakerNameShareItsState() {
        assertThat(ClientProxy.unwrap(first)).isInstanceOf(FirstJournal$Resilient.class);
        first.failing(true);
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> first.post("x")).isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> second.post("y")).isInstanceOf(CallNotPermittedException.class);
        assertThat(second.calls()).isZero();
        assertThat(registry.snapshot().circuitBreakers()).singleElement().satisfies(breaker -> {
            assertThat(breaker.id()).isEqualTo("shared-journal");
            assertThat(breaker.state()).isEqualTo(CircuitBreakerState.OPEN);
        });
    }

    @Test
    void propertiesOverrideAnUnlockedAnnotationButNotALockedOne() {
        assertThat(ClientProxy.unwrap(locked)).isInstanceOf(LockedClient$Resilient.class);
        assertThatThrownBy(locked::locked).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(locked::open).isInstanceOf(IllegalStateException.class);
        assertThat(locked.lockedAttempts()).as("the locked annotation wins").isEqualTo(2);
        assertThat(locked.openAttempts()).as("the property wins over the annotation").isEqualTo(5);
        assertThat(registry.lockedOverrides()).extracting(LockedOverride::id).containsExactly("locked-client");
    }
}
