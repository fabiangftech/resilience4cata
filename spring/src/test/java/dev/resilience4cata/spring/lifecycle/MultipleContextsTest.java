package dev.resilience4cata.spring.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.spring.autoconfigure.ResilienceAutoConfiguration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Several Spring contexts in one JVM, as Spring's test context cache keeps them: each has its own registry, so equal
 * instance names never share state; the library's threads are the JVM's, so starting contexts one after another does
 * not add threads; and a context closed while an async call waits for its retry lets that call finish. On the system
 * clock (no {@code ManualClock}).
 */
class MultipleContextsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ResilienceAutoConfiguration.class))
            .withBean(Ledger.class);

    @Test
    void twoContextsAliveTogetherKeepTheirOwnState() {
        runner.run(first -> {
            Ledger ledger = first.getBean(Ledger.class);
            ledger.failing = true;
            for (int i = 0; i < 2; i++) {
                assertThatThrownBy(() -> ledger.post("a")).isInstanceOf(IllegalStateException.class);
            }
            assertThat(first.getBean(ResilienceRegistry.class).circuitBreaker("ledger").state())
                    .isEqualTo(CircuitBreakerState.OPEN);

            runner.run(second -> {
                assertThat(second.getBean(ResilienceRegistry.class)).isNotSameAs(first.getBean(ResilienceRegistry.class));
                assertThat(second.getBean(ResilienceRegistry.class).circuitBreaker("ledger").state())
                        .as("the same name in another context").isEqualTo(CircuitBreakerState.CLOSED);
                assertThat(second.getBean(Ledger.class).post("b")).isEqualTo("posted b");
            });
            assertThatThrownBy(() -> ledger.post("c")).as("the first context's breaker is still open")
                    .isInstanceOf(CallNotPermittedException.class);
        });
    }

    @Test
    void contextsOneAfterAnotherAddNoLibraryThreads() {
        runner.run(context -> context.getBean(Ledger.class).postLater("warm-up").toCompletableFuture().join());
        long before = libraryThreads();
        for (int i = 0; i < 20; i++) {
            runner.run(context -> assertThat(context.getBean(Ledger.class).postLater("x").toCompletableFuture()
                    .get(5, TimeUnit.SECONDS)).isEqualTo("posted x"));
        }
        assertThat(libraryThreads()).as("platform threads named resilience4cata-*").isEqualTo(before);
    }

    @Test
    void aContextClosedWhileACallWaitsForItsRetryLetsTheCallFinish() throws Exception {
        List<Throwable> uncaught = new CopyOnWriteArrayList<>();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> uncaught.add(failure));
        try {
            java.util.concurrent.atomic.AtomicReference<CompletableFuture<String>> pending = new java.util.concurrent.atomic.AtomicReference<>();
            runner.run(context -> pending.set(context.getBean(Ledger.class).postLater("late").toCompletableFuture()));
            assertThat(pending.get()).as("closed while the retry waits").isNotDone();
            assertThat(pending.get().get(5, TimeUnit.SECONDS)).isEqualTo("posted late");
            assertThat(uncaught).isEmpty();
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    private static long libraryThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> !thread.isVirtual() && thread.getName().startsWith("resilience4cata"))
                .count();
    }
}
