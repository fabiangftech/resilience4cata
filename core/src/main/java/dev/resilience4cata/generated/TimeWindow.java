package dev.resilience4cata.generated;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Sliding window over the calls of the last {@code seconds} seconds, in one-second buckets.
 *
 * <p>Every success increments a {@link LongAdder}, which spreads concurrent increments over several
 * cells so that cores do not fight over one cache line. Failures and slow calls are rarer and use
 * plain atomic counters. A bucket that belongs to an older second is replaced, with compare-and-set,
 * by a new one: that is the only allocation, at most once per bucket per second.
 *
 * <p>Successes cannot be skipped here (unlike {@link CountWindow}): every call must be counted, or
 * the failure rate would be computed over too few calls. Rates are evaluated after every failure or
 * slow call, and after the first call of each second, so crossing {@code minimumCalls} with
 * successes alone is noticed within one second.
 *
 * <p>Seconds are counted from the window's creation ({@code origin}), never from the clock's zero:
 * {@code nanoTime} values are only meaningful as differences, and dividing a raw value would number the
 * seconds wrongly where the clock wraps around {@code Long.MAX_VALUE} (every older bucket would then
 * look newer than the current second and swallow its calls).
 */
final class TimeWindow implements SlidingWindow {

    private static final long SECOND = 1_000_000_000L;

    private final int seconds;
    private final long origin;
    private final AtomicReferenceArray<Bucket> buckets;

    /**
     * @param seconds the window's length
     * @param origin the current time, from which the window's seconds are counted
     */
    TimeWindow(int seconds, long origin) {
        this.seconds = seconds;
        this.origin = origin;
        this.buckets = new AtomicReferenceArray<>(seconds);
    }

    @Override
    @SuppressWarnings("java:S6213") // record is only a contextual keyword; renaming would touch every window caller
    public int record(int outcome, long now) {
        long second = Math.floorDiv(now - origin, SECOND);
        int index = (int) Math.floorMod(second, (long) seconds);
        Bucket bucket = buckets.get(index);
        boolean created = false;
        while (bucket == null || bucket.second < second) {
            Bucket fresh = new Bucket(second);
            if (buckets.compareAndSet(index, bucket, fresh)) {
                bucket = fresh;
                created = true;
                break;
            }
            bucket = buckets.get(index);
        }
        // A thread whose clock reading is a little older can find a newer bucket here: it records into it.
        bucket.calls.increment();
        if ((outcome & FAILURE) != 0) {
            bucket.failures.incrementAndGet();
        }
        if ((outcome & SLOW) != 0) {
            bucket.slowCalls.incrementAndGet();
        }
        return outcome != 0 || created ? EVALUATE : 0;
    }

    @Override
    public boolean exceeds(int minimumCalls, float failureRateThreshold, float slowCallRateThreshold,
            boolean timed, long now) {
        long second = Math.floorDiv(now - origin, SECOND);
        long calls = 0;
        long failures = 0;
        long slowCalls = 0;
        for (int i = 0; i < seconds; i++) {
            Bucket bucket = buckets.get(i);
            if (bucket != null && bucket.second > second - seconds && bucket.second <= second) {
                calls += bucket.calls.sum();
                failures += bucket.failures.get();
                slowCalls += bucket.slowCalls.get();
            }
        }
        return calls >= minimumCalls && calls > 0
                && (failures * 100f >= failureRateThreshold * calls
                        || (timed && slowCalls * 100f >= slowCallRateThreshold * calls));
    }

    @Override
    public int[] counts(long now) {
        long second = Math.floorDiv(now - origin, SECOND);
        long calls = 0;
        long failures = 0;
        long slowCalls = 0;
        for (int i = 0; i < seconds; i++) {
            Bucket bucket = buckets.get(i);
            if (bucket != null && bucket.second > second - seconds && bucket.second <= second) {
                calls += bucket.calls.sum();
                failures += bucket.failures.get();
                slowCalls += bucket.slowCalls.get();
            }
        }
        // A failure is counted after its call, so a reader can see it before or without the call.
        int recorded = (int) Math.min(calls, Integer.MAX_VALUE);
        return new int[] {recorded, Math.clamp(failures, 0, recorded), Math.clamp(slowCalls, 0, recorded)};
    }

    @Override
    public boolean onlyFastSuccesses() {
        return false;
    }

    private static final class Bucket {
        final long second;
        final LongAdder calls = new LongAdder();
        final AtomicInteger failures = new AtomicInteger();
        final AtomicInteger slowCalls = new AtomicInteger();

        Bucket(long second) {
            this.second = second;
        }
    }
}
