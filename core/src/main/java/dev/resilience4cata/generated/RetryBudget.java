package dev.resilience4cata.generated;

import dev.resilience4cata.HedgeConfig;
import dev.resilience4cata.HedgeSnapshot;
import dev.resilience4cata.RetryConfig;
import dev.resilience4cata.RetrySnapshot;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.time.Duration;

/**
 * The retry budget of one retry instance, shared by every caller of that instance: retries in the last
 * {@code budgetWindow} may not exceed {@code budgetMinRetriesPerSecond × window seconds + budgetPercent %
 * of the calls} made in that window. The ratio follows Finagle's {@code RetryBudget}
 * ({@code percentCanRetry} plus a {@code minRetriesPerSec} reserve over a ttl). A hedge instance uses the
 * same class for its hedge budget, with hedges in place of retries.
 *
 * <p>Cost rules. Each call (not each attempt) is counted by {@link #onCall()}: one plain read-and-write
 * (lossy: a race on a shared stripe can lose a call, which only lowers the budget) on a counter striped by
 * a hash of the thread id, each stripe on its own 128-byte line, so that threads do not write
 * to a shared cache line. No clock is read there. Everything else runs after a failure, in
 * {@link #tryAcquire(long)}: one clock read (by the caller), a sum of the stripes, and a compare-and-set
 * on the shared retry counter only when a retry is granted. An exhausted budget only reads.
 *
 * <p>The window. Calls and retries are cumulative counters. {@link Marks} keeps their values at the
 * first failure seen in each of the last {@link #BUCKETS} tenths of the window, so the window counts what
 * happened since the mark of its oldest tenth. Marks are written only by failures (one immutable object,
 * swapped by compare-and-set, at most once per tenth), never by successes, so the time of a call is not
 * known: calls made while nothing failed are dated at the last mark before them. After a quiet period
 * longer than the window they have all expired, and the budget starts again from its reserve plus the
 * calls that follow: it errs on the side of fewer retries. Counts are approximate under concurrency (a
 * stripe read while being incremented, or a call lost to a race on a shared stripe), always on the low side.
 */
final class RetryBudget {

    /** Number of marks: the window moves forward one tenth at a time. */
    static final int BUCKETS = 10;

    /** Longs per cell: 128 bytes, one cache line on Apple silicon, two on most other CPUs. */
    private static final int STRIDE = 16;
    /** The retry counter's cell; call stripes start one cell after it. */
    private static final int RETRIES = STRIDE;
    private static final int FIRST_CALLS = 2 * STRIDE;

    private static final VarHandle CELL = MethodHandles.arrayElementVarHandle(long[].class);
    private static final VarHandle MARKS;

    static {
        try {
            MARKS = MethodHandles.lookup().findVarHandle(RetryBudget.class, "marks", Marks.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Padding, the retry counter, then one cell of call counts per stripe, then padding. */
    private final long[] cells;
    private final int mask;
    private final long bucketNanos;
    private final double fraction;
    private final double reserve;
    private final long origin;
    // java:S1845: the field and its VarHandle share a name, the usual VarHandle naming convention.
    @SuppressWarnings({"unused", "FieldMayBeFinal", "java:S1845"}) // accessed through MARKS
    private Marks marks;

    /**
     * @param config a configuration with {@link RetryConfig#hasBudget() a budget}
     * @param now the current time, which starts the first tenth of the window
     */
    RetryBudget(RetryConfig config, long now) {
        this(config, now, stripes(Runtime.getRuntime().availableProcessors()));
    }

    /** Package-private, for tests: a fixed number of stripes (a power of two). */
    RetryBudget(RetryConfig config, long now, int stripes) {
        this(config.budgetWindow(), config.budgetPercent(), config.budgetMinRetriesPerSecond(), now, stripes);
    }

    /**
     * The budget of a hedge instance: the same window, with hedges in place of retries ({@link #onCall()} counts
     * each call, {@link #tryAcquire(long)} takes one hedge).
     *
     * @param config a configuration with {@link HedgeConfig#hasBudget() a budget}
     * @param now the current time, which starts the first tenth of the window
     */
    RetryBudget(HedgeConfig config, long now) {
        this(config.budgetWindow(), config.budgetPercent(), config.budgetMinHedgesPerSecond(), now,
                stripes(Runtime.getRuntime().availableProcessors()));
    }

    private RetryBudget(Duration window, double percent, int perSecond, long now, int stripes) {
        long windowNanos = window.toNanos();
        this.cells = new long[(stripes + 3) * STRIDE];
        this.mask = stripes - 1;
        this.bucketNanos = Math.max(1, windowNanos / BUCKETS);
        this.fraction = percent / 100;
        this.reserve = perSecond * (windowNanos / 1e9);
        this.origin = now;
        this.marks = new Marks(0, new long[BUCKETS], new long[BUCKETS]);
    }

    /** The smallest power of two that is at least twice {@code processors}, capped at 64. */
    static int stripes(int processors) {
        int wanted = Math.min(64, 2 * Math.max(1, processors));
        return Integer.highestOneBit(wanted - 1) << 1;
    }

    /** Counts one call. On the success path: no clock, no allocation, a write to this thread's stripe only. */
    void onCall() {
        int index = FIRST_CALLS + stripe(Thread.currentThread().threadId(), mask) * STRIDE;
        // Lossy on purpose: a plain read-and-write, no compare-and-set (measured on Graviton3: 9.7 → 3.5 ns per
        // successful call). Two threads that share a stripe and race can lose one call, which only lowers the
        // budget: fewer retries, never more. The counts guard no other memory, so opaque access is enough.
        CELL.setOpaque(cells, index, (long) CELL.getOpaque(cells, index) + 1);
    }

    /**
     * The stripe of a thread. Thread ids often follow a pattern (pool threads created together, ids that are
     * multiples of the stripe count), so the id is mixed first (Fibonacci hashing): taking its low bits
     * directly put every benchmark thread on one stripe.
     */
    static int stripe(long threadId, int mask) {
        return (int) ((threadId * 0x9E3779B97F4A7C15L) >>> 40) & mask;
    }

    /**
     * Takes one retry from the budget, after a failure that would otherwise be retried.
     *
     * @param now the current time
     * @return {@code false} if the budget is exhausted: nothing was written, except the window's marks
     */
    boolean tryAcquire(long now) {
        long bucket = Math.floorDiv(now - origin, bucketNanos);
        Marks current = (Marks) MARKS.getOpaque(this);
        if (bucket > current.bucket) {
            current = advance(current, bucket);
        }
        // current.bucket >= bucket now; a thread whose clock reading is older uses the newer marks.
        int oldest = (int) Math.floorMod(current.bucket - BUCKETS + 1, (long) BUCKETS);
        long calls = Math.max(0, sumCalls() - current.calls[oldest]);
        double allowed = reserve + fraction * calls;
        long base = current.retries[oldest];
        long retries = (long) CELL.getOpaque(cells, RETRIES);
        while (retries - base + 1 <= allowed) {
            if (CELL.weakCompareAndSetPlain(cells, RETRIES, retries, retries + 1)) {
                return true;
            }
            retries = (long) CELL.getOpaque(cells, RETRIES);
        }
        return false;
    }

    /** Marks the tenths from {@code marks.bucket + 1} to {@code bucket} with the current counts. Rare: once per tenth. */
    private Marks advance(Marks marks, long bucket) {
        while (true) {
            long calls = sumCalls();
            long retries = (long) CELL.getOpaque(cells, RETRIES);
            long[] callMarks = marks.calls.clone();
            long[] retryMarks = marks.retries.clone();
            for (long b = Math.max(marks.bucket + 1, bucket - BUCKETS + 1); b <= bucket; b++) {
                int slot = (int) Math.floorMod(b, (long) BUCKETS);
                callMarks[slot] = calls;
                retryMarks[slot] = retries;
            }
            Marks next = new Marks(bucket, callMarks, retryMarks);
            Marks witness = (Marks) MARKS.compareAndExchange(this, marks, next);
            if (witness == marks) {
                return next;
            }
            if (witness.bucket >= bucket) {
                return witness;
            }
            marks = witness;
        }
    }

    private long sumCalls() {
        long sum = 0;
        for (int index = FIRST_CALLS; index <= FIRST_CALLS + mask * STRIDE; index += STRIDE) {
            sum += (long) CELL.getOpaque(cells, index);
        }
        return sum;
    }

    /** Reads the window without changing it; off the call path. */
    RetrySnapshot.Budget snapshot(long now) {
        long[] window = window(now);
        return new RetrySnapshot.Budget(window[0], window[1], window[2]);
    }

    /** Same as {@link #snapshot(long)}, for a hedge: hedges in place of retries. */
    HedgeSnapshot.Budget hedgeSnapshot(long now) {
        long[] window = window(now);
        return new HedgeSnapshot.Budget(window[0], window[1], window[2]);
    }

    /** Calls, retries (or hedges) and allowed ones in the window; off the call path. */
    private long[] window(long now) {
        long bucket = Math.floorDiv(now - origin, bucketNanos);
        Marks current = (Marks) MARKS.getOpaque(this);
        long callsNow = sumCalls();
        long retriesNow = (long) CELL.getOpaque(cells, RETRIES);
        long oldest = Math.max(bucket, current.bucket) - BUCKETS + 1;
        long calls;
        long retries;
        if (oldest > current.bucket) { // every mark is older than the window: what came after is dated at the last one
            calls = 0;
            retries = 0;
        } else {
            int slot = (int) Math.floorMod(oldest, (long) BUCKETS);
            calls = Math.max(0, callsNow - current.calls[slot]);
            retries = Math.max(0, retriesNow - current.retries[slot]);
        }
        return new long[] {calls, retries, (long) (reserve + fraction * calls)};
    }

    /**
     * The cumulative counts at the first failure seen in each of the last {@link #BUCKETS} tenths of the
     * window, indexed by tenth modulo {@link #BUCKETS}. Immutable.
     */
    private static final class Marks {
        final long bucket;
        final long[] calls;
        final long[] retries;

        Marks(long bucket, long[] calls, long[] retries) {
            this.bucket = bucket;
            this.calls = calls;
            this.retries = retries;
        }
    }
}
