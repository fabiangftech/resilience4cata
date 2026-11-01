package dev.resilience4cata.generated;

import dev.resilience4cata.Ticker;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.function.LongConsumer;

/**
 * A ticker for stress tests: it never moves by itself and only records what is scheduled on it, so
 * that actors can run a scheduled task themselves. A task is pending until it runs or is cancelled.
 */
final class RecordingTicker implements Ticker {

    private volatile long now;
    final List<FutureTask<Void>> scheduled = new CopyOnWriteArrayList<>();

    void set(long nanos) {
        now = nanos;
    }

    @Override
    public long read() {
        return now;
    }

    @Override
    public void sleep(long nanos) {
        throw new UnsupportedOperationException("stress tests never sleep");
    }

    @Override
    public Future<?> schedule(long delayNanos, Runnable task) {
        FutureTask<Void> future = new FutureTask<>(task, null);
        scheduled.add(future);
        return future;
    }

    @Override
    public void onAdvance(LongConsumer listener) {
        // never advances
    }

    /** Scheduled tasks neither run nor cancelled. */
    int pending() {
        int count = 0;
        for (FutureTask<Void> task : scheduled) {
            if (!task.isDone()) {
                count++;
            }
        }
        return count;
    }
}
