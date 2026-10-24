package dev.resilience4cata.generated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.TimeoutConfig;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TimeoutPolicyTest {

    private static final long MS = 1_000_000L;

    private final Watchdog watchdog = new Watchdog(0);

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    private TimeoutPolicy timeout(Duration timeout) {
        return new TimeoutPolicy("test", TimeoutConfig.builder().timeout(timeout).build(), watchdog);
    }

    @Test
    void aCallThatEndsInTimeIsNotInterrupted() {
        TimeoutPolicy timeout = timeout(Duration.ofMillis(100));
        TimeoutPolicy.Scope scope = timeout.enter();
        watchdog.tick(50 * MS);
        timeout.succeeded(scope);

        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        assertThat(scope.state()).isEqualTo(TimeoutPolicy.IDLE);
    }

    @Test
    void anOverdueCallIsInterruptedAndTheInterruptIsClearedAfterwards() {
        TimeoutPolicy timeout = timeout(Duration.ofMillis(100));
        TimeoutPolicy.Scope scope = timeout.enter();
        long slack = watchdog.tick();
        watchdog.tick(100 * MS + slack);

        assertThat(Thread.currentThread().isInterrupted()).as("interrupted by the watchdog").isTrue();
        assertThatThrownBy(() -> timeout.succeeded(scope))
                .isInstanceOf(CallTimeoutException.class)
                .hasMessage("Call to 'test' timed out after PT0.1S")
                .hasNoCause();
        assertThat(Thread.currentThread().isInterrupted()).as("interrupt cleared").isFalse();
    }

    @Test
    void neverInterruptsBeforeTimeoutPlusOneTick() {
        TimeoutPolicy timeout = timeout(Duration.ofMillis(100));
        TimeoutPolicy.Scope scope = timeout.enter();
        long slack = watchdog.tick();
        watchdog.tick(100 * MS + slack - 1);

        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        timeout.succeeded(scope);
    }

    @Test
    void theTickFollowsTheShortestTimeout() {
        timeout(Duration.ofSeconds(10));
        assertThat(watchdog.tick()).isEqualTo(50 * MS);
        timeout(Duration.ofMillis(160));
        assertThat(watchdog.tick()).isEqualTo(10 * MS);
        timeout(Duration.ofMillis(1));
        assertThat(watchdog.tick()).isEqualTo(MS);
    }

    @Test
    void whatTheInterruptedCallThrewBecomesTheCause() {
        TimeoutPolicy timeout = timeout(Duration.ofMillis(100));
        TimeoutPolicy.Scope scope = timeout.enter();
        watchdog.tick(Duration.ofSeconds(1).toNanos());
        InterruptedException thrown = new InterruptedException();

        assertThatThrownBy(() -> timeout.failed(scope, thrown))
                .isInstanceOf(CallTimeoutException.class)
                .hasCause(thrown);
    }

    @Test
    void failuresOfCallsThatDidNotTimeOutAreLeftToTheCaller() {
        TimeoutPolicy timeout = timeout(Duration.ofMillis(100));
        TimeoutPolicy.Scope scope = timeout.enter();
        timeout.failed(scope, new IOException()); // returns: the caller rethrows its own exception
        assertThat(scope.state()).isEqualTo(TimeoutPolicy.IDLE);
    }

    @Test
    void nestedTimeoutsAreIndependent() {
        TimeoutPolicy outer = timeout(Duration.ofSeconds(10));
        TimeoutPolicy inner = timeout(Duration.ofMillis(100));
        TimeoutPolicy.Scope outerScope = outer.enter();
        TimeoutPolicy.Scope innerScope = inner.enter();
        assertThat(innerScope).isNotSameAs(outerScope);

        watchdog.tick(Duration.ofSeconds(1).toNanos());
        assertThatThrownBy(() -> inner.succeeded(innerScope)).isInstanceOf(CallTimeoutException.class);
        outer.succeeded(outerScope); // the outer call still had time

        assertThat(outer.enter()).as("scopes are reused once released").isSameAs(outerScope);
    }

    @Test
    void aWatchdogThatReadAnEarlierCallCannotInterruptTheNextOne() {
        // Replays the race deterministically: the watchdog reads the stamp of an overdue call, the
        // call ends and the thread starts another one in the same scope, then the watchdog acts.
        TimeoutPolicy timeout = timeout(Duration.ofMillis(100));
        TimeoutPolicy.Scope first = timeout.enter();
        long seenByWatchdog = first.stampForTest();
        timeout.succeeded(first);
        TimeoutPolicy.Scope second = timeout.enter();
        assertThat(second).isSameAs(first);

        second.expireIfOverdue(Duration.ofSeconds(1).toNanos(), seenByWatchdog);

        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        timeout.succeeded(second);
    }

    @Test
    void disabledTimeoutsDoNothing() {
        TimeoutPolicy timeout = new TimeoutPolicy("test", TimeoutConfig.builder().enabled(false).build(), null);
        assertThat(timeout.enter()).isNull();
        timeout.succeeded(null);
        timeout.failed(null, new IOException());
    }

    @Test
    void deadThreadsAreForgotten() throws InterruptedException {
        TimeoutPolicy timeout = timeout(Duration.ofMillis(100));
        Thread thread = Thread.ofPlatform().start(() -> timeout.succeeded(timeout.enter()));
        thread.join();
        assertThat(watchdog.registeredThreads()).isEqualTo(1);

        watchdog.tick(MS);
        assertThat(watchdog.registeredThreads()).isZero();
    }

    @Test
    void theSharedWatchdogInterruptsABlockedCall() throws InterruptedException {
        TimeoutPolicy timeout = new TimeoutPolicy("shared", TimeoutConfig.builder().timeout(Duration.ofMillis(50)).build());
        for (Thread.Builder builder : new Thread.Builder[] {Thread.ofPlatform(), Thread.ofVirtual()}) {
            AtomicReference<Throwable> outcome = new AtomicReference<>();
            AtomicReference<Boolean> interruptedAfter = new AtomicReference<>();
            long start = System.nanoTime();
            Thread thread = builder.start(() -> {
                TimeoutPolicy.Scope scope = timeout.enter();
                try {
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException e) {
                        timeout.failed(scope, e);
                        throw new AssertionError("expected a timeout", e);
                    }
                    timeout.succeeded(scope);
                } catch (Throwable e) {
                    outcome.set(e);
                }
                interruptedAfter.set(Thread.currentThread().isInterrupted());
            });
            thread.join();
            long elapsedMillis = (System.nanoTime() - start) / MS;

            assertThat(outcome.get()).as(builder.getClass().getSimpleName())
                    .isInstanceOf(CallTimeoutException.class).hasCauseInstanceOf(InterruptedException.class);
            assertThat(interruptedAfter.get()).isFalse();
            assertThat(elapsedMillis).isBetween(50L, 2_000L);
        }
    }

    @Test
    void fastCallsWithTheSharedWatchdogAreNeverInterrupted() {
        TimeoutPolicy timeout = new TimeoutPolicy("shared-fast", TimeoutConfig.builder().timeout(Duration.ofMillis(20)).build());
        for (int i = 0; i < 200_000; i++) {
            TimeoutPolicy.Scope scope = timeout.enter();
            timeout.succeeded(scope);
            assertThat(Thread.interrupted()).isFalse();
        }
    }
}
