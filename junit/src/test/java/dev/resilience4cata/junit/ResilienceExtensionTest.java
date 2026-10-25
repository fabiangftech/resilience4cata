package dev.resilience4cata.junit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectNestedClass;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.junit.fixtures.Fixtures;
import dev.resilience4cata.junit.fixtures.JitteredRetry;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.testkit.engine.EngineExecutionResults;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;

class ResilienceExtensionTest {

    @BeforeEach
    void clear() {
        Fixtures.SEEN.clear();
    }

    @Test
    void oneTestGetsOneClockAndOneRegistryEverywhereAndTheNextTestNewOnes() {
        run(selectClass(Fixtures.SameInstancesWithinATest.class)).testEvents()
                .assertStatistics(s -> s.started(2).succeeded(2));

        List<Object> seen = Fixtures.SEEN;
        int end = seen.indexOf("end");
        List<Object> first = seen.subList(0, end);
        List<Object> second = seen.subList(end + 1, seen.size() - 1);
        assertThat(first.size() + second.size()).isEqualTo(13); // per test: before (2), the test (3 or 2), after (2)
        for (List<Object> test : List.of(first, second)) {
            assertThat(test).filteredOn(ManualClock.class::isInstance).allSatisfy(c -> assertThat(c).isSameAs(test.get(0)));
            assertThat(test).filteredOn(ResilienceRegistry.class::isInstance).allSatisfy(r -> assertThat(r).isSameAs(test.get(1)));
        }
        assertThat(second.get(0)).isNotSameAs(first.get(0));
        assertThat(second.get(1)).isNotSameAs(first.get(1));
    }

    @Test
    void theRegistryFollowsTheClockWithTheSeedAndStartOfTheAnnotation() {
        run(selectMethod(Fixtures.WithSettings.class, "clockAndRegistry", ManualClock.class.getName() + ","
                + ResilienceRegistry.class.getName())).testEvents()
                .assertStatistics(s -> s.succeeded(1));

        assertThat(Fixtures.SEEN).containsExactly(Long.MAX_VALUE - 5, delaysWithSeed(7));
        assertThat(delaysWithSeed(7)).isNotEqualTo(delaysWithSeed(ResilienceTesting.DEFAULT_SEED));
    }

    @Test
    void anAnnotationOnTheMethodWinsOverTheClass() {
        run(selectMethod(Fixtures.WithSettings.class, "methodSettingsWin", ManualClock.class.getName() + ","
                + ResilienceRegistry.class.getName())).testEvents()
                .assertStatistics(s -> s.succeeded(1));

        assertThat(Fixtures.SEEN).containsExactly(0L, delaysWithSeed(8)); // nothing merged from the class
    }

    @Test
    void nestedTestsTakeTheSettingsOfTheEnclosingClass() {
        run(selectNestedClass(List.of(Fixtures.WithSettings.class), Fixtures.WithSettings.Inner.class)).testEvents()
                .assertStatistics(s -> s.succeeded(1));

        assertThat(Fixtures.SEEN).containsExactly(Long.MAX_VALUE - 5);
    }

    @Test
    void plainExtendWithUsesTheDefaults() {
        run(selectClass(Fixtures.PlainExtendWith.class)).testEvents().assertStatistics(s -> s.succeeded(1));

        assertThat(Fixtures.SEEN).containsExactly(0L, delaysWithSeed(ResilienceTesting.DEFAULT_SEED));
    }

    @Test
    void configureMethodsRunSuperclassFirstAndConfigureTheRegistry() {
        run(selectMethod(Fixtures.ConfiguresTheRegistry.class, "seesTheNamedConfigs",
                ResilienceRegistry.class.getName())).testEvents()
                .assertStatistics(s -> s.succeeded(1));

        assertThat(Fixtures.SEEN).containsExactly("base", "own", 3, 5);
    }

    @Test
    void aTestThatOnlyTakesTheClockDoesNotBuildTheRegistry() {
        run(selectMethod(Fixtures.ConfiguresTheRegistry.class, "clockOnlyDoesNotBuildTheRegistry",
                ManualClock.class.getName())).testEvents()
                .assertStatistics(s -> s.succeeded(1));

        assertThat(Fixtures.SEEN).containsExactly("clock only");
    }

    @Test
    void enclosingClassesConfigureBeforeNestedOnes() {
        run(selectNestedClass(List.of(Fixtures.ConfiguresTheRegistry.class), Fixtures.ConfiguresTheRegistry.Inner.class))
                .testEvents().assertStatistics(s -> s.succeeded(1));

        assertThat(Fixtures.SEEN).containsExactly("base", "own", "inner", "test");
    }

    @Test
    void aConfigureMethodWithTheWrongSignatureFailsTheTest() {
        Throwable failure = onlyFailure(run(selectClass(Fixtures.WrongConfigureMethod.class)).testEvents());

        // JUnit 5 wraps it in a ParameterResolutionException, JUnit 6 does not
        Throwable cause = failure instanceof ExtensionConfigurationException ? failure : failure.getCause();
        assertThat(cause).isInstanceOf(ExtensionConfigurationException.class)
                .hasMessageContaining("configure(dev.resilience4cata.ResilienceRegistry$Builder)")
                .hasMessageContaining("must be a non-private void method with one ResilienceRegistry.Builder parameter");
    }

    @Test
    void constructorsCannotTakeTheClock() {
        Throwable failure = onlyFailure(run(selectClass(Fixtures.ConstructorInjection.class)).testEvents());

        assertThat(failure).isInstanceOf(ParameterResolutionException.class)
                .hasMessageContaining("cannot inject ManualClock")
                .hasMessageContaining("take them as parameters of the test method or of a @BeforeEach method");
    }

    @Test
    void beforeAllMethodsCannotTakeTheClock() {
        Throwable failure = onlyFailure(run(selectClass(Fixtures.BeforeAllInjection.class)).containerEvents());

        assertThat(failure).isInstanceOf(ParameterResolutionException.class).hasMessageContaining("cannot inject ManualClock");
    }

    @Test
    void failOnPendingTasksFailsAPassingTestThatLeftATask() {
        Throwable failure = onlyFailure(run(selectMethod(Fixtures.PendingTasks.class, "leavesATask",
                ManualClock.class.getName())).testEvents());

        assertThat(failure).isInstanceOf(AssertionError.class)
                .hasMessageStartingWith("The test ended with 1 task(s) still pending on its clock")
                .hasMessageContaining("pending task delays (ns)=[1000000000]")
                .hasMessageContaining("clock.advanceUntilIdle()");
    }

    @Test
    void failOnPendingTasksAcceptsTasksThatRanOrWereCancelled() {
        run(selectMethod(Fixtures.PendingTasks.class, "runsItsTask", ManualClock.class.getName())).testEvents()
                .assertStatistics(s -> s.succeeded(1));
        run(selectMethod(Fixtures.PendingTasks.class, "cancelsItsTask", ManualClock.class.getName())).testEvents()
                .assertStatistics(s -> s.succeeded(1));
    }

    @Test
    void failOnPendingTasksKeepsTheTestsOwnFailure() {
        Throwable failure = onlyFailure(run(selectMethod(Fixtures.PendingTasks.class, "failsOnItsOwn",
                ManualClock.class.getName())).testEvents());

        assertThat(failure).hasMessageContaining("the test's own failure").hasNoSuppressedExceptions();
    }

    @Test
    void pendingTasksAreAllowedByDefault() {
        run(selectClass(Fixtures.PendingTasksAllowed.class)).testEvents().assertStatistics(s -> s.succeeded(1));
    }

    private static EngineExecutionResults run(DiscoverySelector selector) {
        return EngineTestKit.engine("junit-jupiter").selectors(selector).execute();
    }

    private static Throwable onlyFailure(Events events) {
        List<Throwable> failures = events.failed().stream()
                .map(e -> e.getRequiredPayload(TestExecutionResult.class).getThrowable().orElseThrow())
                .toList();
        assertThat(failures).hasSize(1);
        return failures.get(0);
    }

    private static Duration delaysWithSeed(long seed) {
        ManualClock clock = new ManualClock();
        return JitteredRetry.delays(ResilienceTesting.registry(clock, seed).build(), clock);
    }
}
