package dev.resilience4cata.junit.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.junit.ConfigureRegistry;
import dev.resilience4cata.junit.ResilienceExtension;
import dev.resilience4cata.junit.ResilienceTest;
import dev.resilience4cata.testkit.ManualClock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Test classes that {@code ResilienceExtensionTest} runs through EngineTestKit; the build never runs them
 * directly. They write what they see to {@link #SEEN}.
 */
public final class Fixtures {

    /** What the fixtures saw, in order; cleared by each test of the extension. */
    public static final List<Object> SEEN = new CopyOnWriteArrayList<>();

    private Fixtures() {
    }

    @ResilienceTest
    public static class SameInstancesWithinATest {
        @BeforeEach
        void before(ManualClock clock, ResilienceRegistry registry) {
            SEEN.add(clock);
            SEEN.add(registry);
        }

        @Test
        void first(ManualClock clock, ResilienceRegistry registry, ManualClock again) {
            SEEN.add(clock);
            SEEN.add(registry);
            SEEN.add(again);
        }

        @Test
        void second(ManualClock clock, ResilienceRegistry registry) {
            SEEN.add(clock);
            SEEN.add(registry);
        }

        @AfterEach
        void after(ManualClock clock, ResilienceRegistry registry) {
            SEEN.add(clock);
            SEEN.add(registry);
            SEEN.add("end");
        }
    }

    @ResilienceTest(seed = 7, startNanos = Long.MAX_VALUE - 5)
    public static class WithSettings {
        @Test
        void clockAndRegistry(ManualClock clock, ResilienceRegistry registry) {
            SEEN.add(clock.read());
            SEEN.add(JitteredRetry.delays(registry, clock));
        }

        @Test
        @ResilienceTest(seed = 8)
        void methodSettingsWin(ManualClock clock, ResilienceRegistry registry) {
            SEEN.add(clock.read());
            SEEN.add(JitteredRetry.delays(registry, clock));
        }

        @Nested
        public class Inner {
            @Test
            void enclosingSettingsApply(ManualClock clock) {
                SEEN.add(clock.read());
            }
        }
    }

    @ExtendWith(ResilienceExtension.class)
    public static class PlainExtendWith {
        @Test
        void defaults(ManualClock clock, ResilienceRegistry registry) {
            SEEN.add(clock.read());
            SEEN.add(JitteredRetry.delays(registry, clock));
        }
    }

    public abstract static class BaseWithConfig {
        @ConfigureRegistry
        static void baseStatic(ResilienceRegistry.Builder builder) {
            SEEN.add("base");
            builder.retry("base", r -> r.maxAttempts(5));
        }
    }

    @ResilienceTest
    public static class ConfiguresTheRegistry extends BaseWithConfig {
        @ConfigureRegistry
        void own(ResilienceRegistry.Builder builder) {
            SEEN.add("own");
            builder.retry("payments", r -> r.maxAttempts(3).delay(Duration.ofSeconds(2)));
        }

        @Test
        void seesTheNamedConfigs(ResilienceRegistry registry) {
            SEEN.add(registry.retryConfig("payments").maxAttempts());
            SEEN.add(registry.retryConfig("base").maxAttempts());
        }

        @Test
        void clockOnlyDoesNotBuildTheRegistry(ManualClock clock) {
            SEEN.add("clock only");
        }

        @Nested
        public class Inner {
            @ConfigureRegistry
            void inner(ResilienceRegistry.Builder builder) {
                SEEN.add("inner");
            }

            @Test
            void outerFirst(ResilienceRegistry registry) {
                SEEN.add("test");
            }
        }
    }

    @ResilienceTest
    public static class WrongConfigureMethod {
        @ConfigureRegistry
        int configure(ResilienceRegistry.Builder builder) {
            return 1;
        }

        @Test
        void test(ResilienceRegistry registry) {
        }
    }

    @ResilienceTest
    public static class ConstructorInjection {
        ConstructorInjection(ManualClock clock) {
        }

        @Test
        void test() {
        }
    }

    @ResilienceTest
    public static class BeforeAllInjection {
        @BeforeAll
        static void beforeAll(ManualClock clock) {
        }

        @Test
        void test() {
        }
    }

    @ResilienceTest(failOnPendingTasks = true)
    public static class PendingTasks {
        @Test
        void leavesATask(ManualClock clock) {
            clock.schedule(Duration.ofSeconds(1).toNanos(), () -> { });
        }

        @Test
        void runsItsTask(ManualClock clock) {
            clock.schedule(Duration.ofSeconds(1).toNanos(), () -> { });
            clock.advanceUntilIdle();
        }

        @Test
        void cancelsItsTask(ManualClock clock) {
            clock.schedule(Duration.ofSeconds(1).toNanos(), () -> { }).cancel(false);
        }

        @Test
        void failsOnItsOwn(ManualClock clock) {
            clock.schedule(Duration.ofSeconds(1).toNanos(), () -> { });
            assertThat(1).as("the test's own failure").isEqualTo(2);
        }
    }

    @ResilienceTest
    public static class PendingTasksAllowed {
        @Test
        void leavesATask(ManualClock clock) {
            clock.schedule(Duration.ofSeconds(1).toNanos(), () -> { });
        }
    }
}
