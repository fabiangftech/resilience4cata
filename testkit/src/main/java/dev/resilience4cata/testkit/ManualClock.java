package dev.resilience4cata.testkit;

import dev.resilience4cata.Ticker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongConsumer;
import org.jspecify.annotations.Nullable;

/**
 * A clock that only moves when told to, for tests. Give it to a registry with
 * {@link ResilienceTesting#registry(ManualClock)} (or {@code ResilienceRegistry.builder().ticker(clock)}),
 * and every policy of that registry follows it:
 *
 * <ul>
 *   <li><b>Sleeps advance the clock instead of blocking.</b> A retry with a 1 s backoff, or a rate
 *       limiter waiting for its next permit, returns at once with the clock 1 s later.</li>
 *   <li><b>Waits are measured on this clock.</b> A circuit breaker stays open until the test calls
 *       {@code advance(waitInOpen)}; the next call is then a half-open probe.</li>
 *   <li><b>Delayed tasks run when the clock passes their deadline</b>, on the thread that calls
 *       {@link #advance}, before it returns: async retry backoff, async rate limiter waits, async
 *       bulkhead wait limits and async timeouts.</li>
 *   <li><b>Synchronous timeouts fire when the clock passes their deadline</b>, exactly (no slack): the
 *       timed call's thread is interrupted. A stub that simulates a slow dependency with
 *       {@link #sleep(Duration)} therefore ends with {@code CallTimeoutException}. The clock still
 *       advances by the whole sleep: time jumps, it does not flow.</li>
 * </ul>
 *
 * <p>Thread-safe. Tasks and listeners run outside the clock's lock, so a task may schedule more tasks,
 * sleep or advance the clock again. Two threads advancing at once both make progress, but the order in
 * which their tasks run is then not deterministic; advance from one thread for exact results.
 *
 * <p>The clock starts at {@code 0} nanoseconds unless another start is given; the value has no meaning
 * except through differences, like {@link System#nanoTime()}.
 */
public final class ManualClock implements Ticker {

    /** Bound on the tasks {@link #advanceUntilIdle()} runs, so that a task that always reschedules itself fails. */
    private static final int MAX_IDLE_TASKS = 100_000;

    private final ReentrantLock lock = new ReentrantLock();
    private final PriorityQueue<Scheduled> tasks = new PriorityQueue<>();
    private final List<LongConsumer> listeners = new CopyOnWriteArrayList<>();
    private final long start;
    private volatile long now;
    private long sequence; // guarded by lock: keeps tasks with the same deadline in scheduling order

    /** Creates a clock at {@code 0}. */
    public ManualClock() {
        this(0L);
    }

    /**
     * Creates a clock at a given reading, for instance close to {@code Long.MAX_VALUE} to check that
     * nothing breaks when {@code nanoTime} wraps around.
     *
     * @param startNanos the first value {@link #read()} returns
     */
    public ManualClock(long startNanos) {
        this.start = startNanos;
        this.now = startNanos;
    }

    @Override
    public long read() {
        return now;
    }

    /** Returns how far the clock has moved since it was created. */
    public Duration elapsed() {
        return Duration.ofNanos(now - start);
    }

    /**
     * Moves the clock forward, running every task that falls due on the way, in deadline order, each
     * with the clock at its deadline, then sets the clock to its final reading.
     *
     * @param duration how far to move; not negative
     */
    public void advance(Duration duration) {
        advance(duration.toNanos());
    }

    /**
     * Moves the clock forward by {@code nanos}; see {@link #advance(Duration)}.
     *
     * @param nanos how far to move; not negative
     */
    public void advance(long nanos) {
        if (nanos < 0) {
            throw new IllegalArgumentException("A clock cannot move backwards: " + nanos + " ns");
        }
        advanceTo(now + nanos);
    }

    /**
     * Advances to the deadline of each pending task in turn until none is left: completes whatever the
     * policies have scheduled, such as every async retry, however long their delays.
     *
     * @return how far the clock moved
     * @throws IllegalStateException if tasks keep scheduling new ones (more than 100,000 in a row)
     */
    public Duration advanceUntilIdle() {
        long from = now;
        for (int i = 0; i < MAX_IDLE_TASKS; i++) {
            long next;
            lock.lock();
            try {
                dropCancelled();
                Scheduled head = tasks.peek();
                if (head == null) {
                    return Duration.ofNanos(now - from);
                }
                next = head.deadline;
            } finally {
                lock.unlock();
            }
            advanceTo(next - now > 0 ? next : now);
        }
        throw new IllegalStateException("Tasks keep scheduling new tasks: gave up after " + MAX_IDLE_TASKS);
    }

    /**
     * Advances the clock by {@code nanos} instead of blocking, as policies do through this clock. Like
     * {@link Thread#sleep}, throws if the thread is interrupted before or while sleeping, which is how a
     * timeout reaches a call sleeping on this clock; the interrupt flag is then cleared.
     *
     * @param nanos how long to sleep; not negative
     * @throws InterruptedException if the thread was interrupted
     */
    @Override
    public void sleep(long nanos) throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        advance(nanos);
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
    }

    /**
     * Same as {@link #sleep(long)}, for stubs that simulate a slow dependency.
     *
     * @param duration how long to sleep; not negative
     * @throws InterruptedException if the thread was interrupted, for instance by a timeout
     */
    public void sleep(Duration duration) throws InterruptedException {
        sleep(duration.toNanos());
    }

    @Override
    public Future<?> schedule(long delayNanos, Runnable task) {
        Objects.requireNonNull(task, "task");
        FutureTask<Void> future = new FutureTask<>(task, null);
        lock.lock();
        try {
            tasks.add(new Scheduled(now + Math.max(delayNanos, 0), sequence++, future));
        } finally {
            lock.unlock();
        }
        return future;
    }

    @Override
    public void onAdvance(LongConsumer listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** Returns the number of tasks waiting for the clock, not counting cancelled ones. */
    public int pendingTasks() {
        lock.lock();
        try {
            dropCancelled();
            int pending = 0;
            for (Scheduled task : tasks) {
                if (!task.future.isDone()) {
                    pending++;
                }
            }
            return pending;
        } finally {
            lock.unlock();
        }
    }

    /** Returns the time until the next pending task falls due, or {@code null} if none is pending. */
    public @Nullable Duration nextTaskIn() {
        lock.lock();
        try {
            dropCancelled();
            Scheduled head = tasks.peek();
            return head == null ? null : Duration.ofNanos(Math.max(head.deadline - now, 0));
        } finally {
            lock.unlock();
        }
    }

    private void advanceTo(long target) {
        while (true) {
            Scheduled due = null;
            long time;
            lock.lock();
            try {
                dropCancelled();
                Scheduled head = tasks.peek();
                if (head != null && head.deadline - target <= 0) {
                    due = tasks.poll();
                    if (head.deadline - now > 0) {
                        now = head.deadline;
                    }
                } else if (target - now > 0) {
                    now = target;
                }
                time = now;
            } finally {
                lock.unlock();
            }
            for (LongConsumer listener : listeners) {
                listener.accept(time);
            }
            if (due == null) {
                return;
            }
            due.future.run(); // a cancelled task does nothing; a failure is kept in its future
        }
    }

    /** Removes cancelled tasks from the head of the queue; others are skipped when they fall due. */
    private void dropCancelled() {
        while (!tasks.isEmpty() && tasks.peek().future.isCancelled()) {
            tasks.poll();
        }
    }

    /** Pending tasks, for error messages: their remaining delays in order. */
    @Override
    public String toString() {
        List<Long> delays = new ArrayList<>();
        lock.lock();
        try {
            for (Scheduled task : tasks) {
                if (!task.future.isDone()) {
                    delays.add(task.deadline - now);
                }
            }
        } finally {
            lock.unlock();
        }
        delays.sort(null);
        return "ManualClock[elapsed=" + elapsed() + ", pending task delays (ns)=" + delays + "]";
    }

    private record Scheduled(long deadline, long sequence, FutureTask<Void> future) implements Comparable<Scheduled> {
        @Override
        public int compareTo(Scheduled other) {
            // By subtraction: readings may wrap around like nanoTime.
            long byDeadline = deadline - other.deadline;
            if (byDeadline != 0) {
                return byDeadline < 0 ? -1 : 1;
            }
            return Long.compare(sequence, other.sequence);
        }
    }
}
