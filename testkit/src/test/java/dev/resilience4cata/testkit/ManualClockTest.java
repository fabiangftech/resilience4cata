package dev.resilience4cata.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ManualClockTest {

    private final ManualClock clock = new ManualClock();

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void movesOnlyWhenAdvanced() {
        assertThat(clock.read()).isZero();
        clock.advance(Duration.ofSeconds(3));
        assertThat(clock.read()).isEqualTo(3_000_000_000L);
        assertThat(clock.elapsed()).isEqualTo(Duration.ofSeconds(3));
        clock.advance(0);
        assertThat(clock.elapsed()).isEqualTo(Duration.ofSeconds(3));
        assertThatIllegalArgumentException().isThrownBy(() -> clock.advance(-1));
    }

    @Test
    void sleepAdvancesInsteadOfBlocking() throws InterruptedException {
        long start = System.nanoTime();
        clock.sleep(Duration.ofHours(1));
        assertThat(clock.elapsed()).isEqualTo(Duration.ofHours(1));
        assertThat(System.nanoTime() - start).isLessThan(Duration.ofSeconds(1).toNanos());
    }

    @Test
    void sleepThrowsAndClearsTheFlagWhenInterruptedBeforeOrDuring() {
        Thread.currentThread().interrupt();
        assertThatThrownBy(() -> clock.sleep(10)).isInstanceOf(InterruptedException.class);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        assertThat(clock.read()).isZero();

        Thread self = Thread.currentThread();
        clock.onAdvance(now -> {
            if (now >= 5) {
                self.interrupt();
            }
        });
        assertThatThrownBy(() -> clock.sleep(10)).isInstanceOf(InterruptedException.class);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        assertThat(clock.read()).isEqualTo(10); // time jumps: the whole sleep is applied
    }

    @Test
    void runsDueTasksInDeadlineOrderWithTheClockAtTheirDeadline() {
        List<String> ran = new ArrayList<>();
        clock.schedule(30, () -> ran.add("c@" + clock.read()));
        clock.schedule(10, () -> ran.add("a@" + clock.read()));
        clock.schedule(10, () -> ran.add("b@" + clock.read())); // same deadline: scheduling order
        clock.schedule(50, () -> ran.add("late"));

        clock.advance(40);

        assertThat(ran).containsExactly("a@10", "b@10", "c@30");
        assertThat(clock.read()).isEqualTo(40);
        assertThat(clock.pendingTasks()).isOne();
        assertThat(clock.nextTaskIn()).isEqualTo(Duration.ofNanos(10));
    }

    @Test
    void cancelledTasksNeverRun() {
        List<String> ran = new ArrayList<>();
        Future<?> cancelled = clock.schedule(10, () -> ran.add("cancelled"));
        clock.schedule(20, () -> ran.add("kept"));
        assertThat(cancelled.cancel(false)).isTrue();
        assertThat(clock.pendingTasks()).isOne();

        clock.advance(30);

        assertThat(ran).containsExactly("kept");
        assertThat(clock.nextTaskIn()).isNull();
    }

    @Test
    void aTaskCanScheduleAnotherThatFallsDueInTheSameAdvance() {
        List<Long> ran = new ArrayList<>();
        clock.schedule(10, () -> {
            ran.add(clock.read());
            clock.schedule(10, () -> ran.add(clock.read()));
        });

        clock.advance(25);

        assertThat(ran).containsExactly(10L, 20L);
    }

    @Test
    void listenersSeeEveryStep() {
        List<Long> seen = new ArrayList<>();
        clock.onAdvance(seen::add);
        clock.schedule(10, () -> { });

        clock.advance(15);

        assertThat(seen).containsExactly(10L, 15L);
    }

    @Test
    void advanceUntilIdleRunsChainsOfTasks() {
        List<Long> ran = new ArrayList<>();
        clock.schedule(Duration.ofSeconds(1).toNanos(), () -> {
            ran.add(clock.read());
            clock.schedule(Duration.ofSeconds(2).toNanos(), () -> ran.add(clock.read()));
        });

        assertThat(clock.advanceUntilIdle()).isEqualTo(Duration.ofSeconds(3));
        assertThat(ran).containsExactly(1_000_000_000L, 3_000_000_000L);
        assertThat(clock.advanceUntilIdle()).isZero();
    }

    @Test
    void advanceUntilIdleGivesUpOnTasksThatRescheduleForever() {
        Runnable forever = new Runnable() {
            @Override
            public void run() {
                clock.schedule(1, this);
            }
        };
        clock.schedule(1, forever);

        assertThatIllegalStateException().isThrownBy(clock::advanceUntilIdle).withMessageContaining("keep scheduling");
    }

    @Test
    void ordersDeadlinesAcrossTheWrapAroundOfNanoTime() {
        ManualClock wrapping = new ManualClock(Long.MAX_VALUE - 5);
        List<String> ran = new ArrayList<>();
        wrapping.schedule(20, () -> ran.add("after wrap"));
        wrapping.schedule(2, () -> ran.add("before wrap"));

        wrapping.advance(30);

        assertThat(ran).containsExactly("before wrap", "after wrap");
        assertThat(wrapping.elapsed()).isEqualTo(Duration.ofNanos(30));
    }

    @Test
    void aFailingTaskDoesNotStopTheClock() {
        Future<?> failing = clock.schedule(1, () -> {
            throw new IllegalStateException("boom");
        });
        clock.advance(2);
        assertThat(failing).isDone();
        assertThat(clock.read()).isEqualTo(2);
    }
}
