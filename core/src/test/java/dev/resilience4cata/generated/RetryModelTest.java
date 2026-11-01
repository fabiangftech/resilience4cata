package dev.resilience4cata.generated;

import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.Classifier;
import dev.resilience4cata.RetryConfig;
import dev.resilience4cata.RetrySnapshot;
import dev.resilience4cata.Ticker;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.concurrent.Future;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link RetryPolicy} through random interleavings of calls of one instance (each a series of failed
 * attempts that ends in a success or when the policy gives up), with and without a retry budget, and checks
 * every decision against a model of docs/en/policies/retry.md: {@code maxAttempts}, {@code abortOn} over
 * {@code retryOn}, an open breaker's {@code CallNotPermittedException} and interrupts never retried, the
 * backoff and its jitter, {@code maxDuration}, and the budget.
 *
 * <p>The budget model keeps every call, retry and budget check with its time. Its window is the one the
 * documentation describes: ten tenths of {@code budgetWindow}, each starting at the first failure seen in
 * it (the counts then are its mark), so that calls made while nothing failed are dated at the last mark
 * before them. Independently of that, every granted retry is checked against the documented limit counted
 * from the actual times: retries in the last ten tenths may not exceed {@code budgetMinRetriesPerSecond ×
 * window + budgetPercent %} of the calls made in them. See {@link ModelRuns} to replay a seed.
 */
class RetryModelTest {

    private static final long MILLI = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;
    private static final int SEEDS = 1500;
    private static final int STEPS = 500;
    private static final int BUCKETS = 10;

    private static final TreeMap<String, Integer> SEEN = new TreeMap<>();

    @AfterAll
    static void everySituationCameUp() {
        if (System.getProperty("test.seed") != null) {
            return;
        }
        for (String situation : List.of("retry", "max attempts", "aborted", "not listed", "open breaker",
                "interrupted", "max duration", "budget exhausted", "budget granted", "classifier", "jitter")) {
            Integer count = SEEN.get(situation);
            if (count == null || count < 50) {
                throw new AssertionError("situation '" + situation + "' came up " + count + " times: " + SEEN);
            }
        }
    }

    private static void seen(String situation) {
        SEEN.merge(situation, 1, Integer::sum);
    }

    @Test
    void matchesTheModel() {
        ModelRuns.forEachSeed(SEEDS, RetryModelTest::run);
    }

    private static void run(long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        boolean budgeted = random.nextInt(4) != 0;
        RetryConfig.Builder builder = RetryConfig.builder()
                .enabled(random.nextInt(20) != 0)
                .maxAttempts(random.nextInt(1, 6))
                .delay(Duration.ofMillis(random.nextInt(0, 300)))
                .backoff(random.nextBoolean() ? RetryConfig.Backoff.FIXED : RetryConfig.Backoff.EXPONENTIAL)
                .multiplier(1 + random.nextInt(0, 30) / 10.0)
                .maxDelay(Duration.ofMillis(random.nextInt(300, 2_000)))
                .jitter(RetryConfig.Jitter.values()[random.nextInt(3)])
                .maxDuration(random.nextBoolean() ? Duration.ZERO : Duration.ofMillis(random.nextInt(1, 3_000)))
                .retryOn(List.of(IOException.class, CallNotPermittedException.class, InterruptedException.class))
                .abortOn(List.of(FileNotFoundException.class));
        if (budgeted) {
            builder.budgetPercent(List.of(0.5, 5.0, 10.0, 20.0, 50.0, 100.0).get(random.nextInt(6)))
                    .budgetMinRetriesPerSecond(random.nextInt(4) == 0 ? 0 : random.nextInt(0, 4))
                    .budgetWindow(Duration.ofMillis(random.nextInt(1_000, 5_000)));
        }
        RetryConfig config = builder.build();
        long start = switch (random.nextInt(3)) {
            case 0 -> 0L;
            case 1 -> -random.nextLong(1L << 50);
            default -> Long.MAX_VALUE - random.nextLong(5 * SECOND); // wraps during the run
        };
        ModelRuns.Clock clock = new ModelRuns.Clock(start);
        long jitterSeed = random.nextLong();
        RetryPolicy retry = new RetryPolicy("model", config,
                PolicyEnvironment.of(new TestTicker(clock), new SplittableRandom(jitterSeed)));
        Model model = new Model(config, start, new SplittableRandom(jitterSeed));
        ModelRuns.Trace trace = new ModelRuns.Trace(seed, config + ", start " + start);
        List<long[]> calls = new ArrayList<>(); // {attempts so far, start}

        for (int step = 0; step < STEPS; step++) {
            int op = random.nextInt(100);
            if (op < 25 || calls.isEmpty()) {
                long callStart = retry.start();
                trace.step("start at " + clock.now + " -> " + callStart);
                trace.equal("start", model.start(clock.now), callStart);
                calls.add(new long[] {0, callStart});
            } else if (op < 75) {
                int index = random.nextInt(calls.size());
                long[] call = calls.get(index);
                int attempts = (int) ++call[0];
                Failure failure = Failure.values()[random.nextInt(Failure.values().length)];
                long delay = failure.decide(retry, attempts, call[1]);
                if (Thread.interrupted() && failure != Failure.INTERRUPTED_THREAD) {
                    throw trace.fail("the interrupt flag was left set");
                }
                trace.step(failure + " after attempt " + attempts + " at " + clock.now + " -> " + delay);
                long expected = model.decide(failure, attempts, call[1], clock.now);
                trace.equal("decision", expected, delay);
                if (delay == RetryPolicy.NO_RETRY) {
                    calls.remove(index);
                } else if (random.nextBoolean()) {
                    boolean slept = retry.pause(delay); // the test ticker's sleep moves the clock
                    trace.step("pause " + delay);
                    trace.check(slept, "pause returned false");
                }
            } else if (op < 85) {
                calls.remove(random.nextInt(calls.size()));
                trace.step("an attempt succeeded");
            } else {
                long[] some = calls.get(random.nextInt(calls.size()));
                long by = switch (random.nextInt(5)) {
                    // to where the next delay would just reach maxDuration (exact without jitter)
                    case 4 -> some[1] + model.maxDuration - model.base((int) some[0] + 1) + random.nextInt(3) - 1
                            - clock.now;
                    case 0 -> random.nextLong(0, 5 * MILLI);
                    case 1 -> random.nextLong(5 * MILLI, 300 * MILLI);
                    case 2 -> model.toNextTenth(clock.now) + random.nextInt(3) - 1;
                    default -> random.nextLong(300 * MILLI, 6 * SECOND);
                };
                by = by >= 0 && by <= 10 * SECOND ? by : 0; // by subtraction: the clock may wrap
                clock.now += by;
                trace.step("advance " + by + " to " + clock.now);
            }
            trace.equal("budget snapshot", String.valueOf(model.snapshot(clock.now)),
                    String.valueOf(retry.budgetSnapshot()));
            if (model.lastGrant != null) {
                trace.check(model.lastGrant.isEmpty(), model.lastGrant);
                model.lastGrant = null;
            }
        }
    }

    /** How an attempt failed, and the {@link RetryPolicy} method generated code calls for it. */
    private enum Failure {
        IO, ABORTED, NOT_LISTED, OPEN_BREAKER, INTERRUPTED_EXCEPTION, INTERRUPTED_THREAD, CLASSIFIED_FAILURE,
        CLASSIFIED_SUCCESS, CLASSIFIED_ABORTED, REJECTED_RESULT;

        long decide(RetryPolicy retry, int attempts, long start) {
            return switch (this) {
                case IO -> retry.onFailure(attempts, new IOException("boom"), start);
                case ABORTED -> retry.onFailure(attempts, new FileNotFoundException("abort"), start);
                case NOT_LISTED -> retry.onFailure(attempts, new IllegalStateException("not listed"), start);
                case OPEN_BREAKER -> retry.onFailure(attempts, new CallNotPermittedException("breaker"), start);
                case INTERRUPTED_EXCEPTION -> retry.onFailure(attempts, new InterruptedException(), start);
                case INTERRUPTED_THREAD -> {
                    Thread.currentThread().interrupt();
                    try {
                        yield retry.onFailure(attempts, new IOException("interrupted"), start);
                    } finally {
                        Thread.interrupted();
                    }
                }
                case CLASSIFIED_FAILURE ->
                        retry.onFailure(attempts, new IllegalStateException("classified"), start, Classifier.Outcome.FAILURE);
                case CLASSIFIED_SUCCESS ->
                        retry.onFailure(attempts, new IOException("classified"), start, Classifier.Outcome.SUCCESS);
                case CLASSIFIED_ABORTED ->
                        retry.onFailure(attempts, new FileNotFoundException("classified"), start, Classifier.Outcome.FAILURE);
                case REJECTED_RESULT -> retry.onFailedResult(attempts, start);
            };
        }
    }

    private static final class Model {
        final boolean enabled;
        final int maxAttempts;
        final long delay;
        final boolean exponential;
        final double multiplier;
        final long maxDelay;
        final RetryConfig.Jitter jitter;
        final long maxDuration;
        final SplittableRandom random;
        final boolean budgeted;
        final long origin;
        final long tenth;
        final double reserve;
        final double fraction;

        /** Times of the calls and of the granted retries. */
        final List<Long> callTimes = new ArrayList<>();
        final List<Long> retryTimes = new ArrayList<>();
        /** Every budget check: {tenth, calls before it, retries before it}, oldest first. */
        final List<long[]> checks = new ArrayList<>();
        /** After a granted retry: "" if the documented limit holds, else what is wrong. */
        String lastGrant;

        Model(RetryConfig config, long now, SplittableRandom random) {
            enabled = config.enabled();
            maxAttempts = config.maxAttempts();
            delay = config.delay().toNanos();
            exponential = config.backoff() == RetryConfig.Backoff.EXPONENTIAL;
            multiplier = config.multiplier();
            maxDelay = config.maxDelay().toNanos();
            jitter = config.jitter();
            maxDuration = config.maxDuration().toNanos();
            this.random = random;
            budgeted = enabled && config.budgetPercent() > 0;
            origin = now;
            long window = config.budgetWindow().toNanos();
            tenth = Math.max(1, window / BUCKETS);
            reserve = config.budgetMinRetriesPerSecond() * (window / 1e9);
            fraction = config.budgetPercent() / 100;
        }

        long start(long now) {
            if (budgeted) {
                callTimes.add(now);
            }
            return maxDuration > 0 ? now : 0;
        }

        long decide(Failure failure, int attempts, long start, long now) {
            if (!enabled) {
                return RetryPolicy.NO_RETRY;
            }
            if (attempts >= maxAttempts) {
                seen("max attempts");
                return RetryPolicy.NO_RETRY;
            }
            switch (failure) {
                case ABORTED, CLASSIFIED_ABORTED -> {
                    seen("aborted");
                    return RetryPolicy.NO_RETRY;
                }
                case NOT_LISTED, CLASSIFIED_SUCCESS -> {
                    seen("not listed");
                    return RetryPolicy.NO_RETRY;
                }
                case OPEN_BREAKER -> { // listed in retryOn, never retried
                    seen("open breaker");
                    return RetryPolicy.NO_RETRY;
                }
                case INTERRUPTED_EXCEPTION, INTERRUPTED_THREAD -> {
                    seen("interrupted");
                    return RetryPolicy.NO_RETRY;
                }
                case CLASSIFIED_FAILURE, REJECTED_RESULT -> seen("classifier");
                default -> {
                    // retried
                }
            }
            long next = jittered(base(attempts));
            if (maxDuration > 0 && now - start + next > maxDuration) {
                seen("max duration");
                return RetryPolicy.NO_RETRY;
            }
            if (budgeted && !budget(now)) {
                seen("budget exhausted");
                return RetryPolicy.NO_RETRY;
            }
            seen("retry");
            return next;
        }

        /** Documented: {@code delay}, or {@code delay × multiplier^(n−1)} capped at {@code maxDelay}. */
        long base(int attempts) {
            if (!exponential) {
                return delay;
            }
            double grown = delay * Math.pow(multiplier, attempts - 1.0);
            return grown >= maxDelay ? maxDelay : (long) grown;
        }

        /** The same draws as the policy's generator: FULL is 0 to the delay, EQUAL half of it to all of it. */
        private long jittered(long base) {
            if (base <= 0 || jitter == RetryConfig.Jitter.NONE) {
                return Math.max(base, 0);
            }
            seen("jitter");
            long low = jitter == RetryConfig.Jitter.FULL ? 0 : base / 2;
            return low + random.nextLong(base - low + 1);
        }

        private long tenthOf(long now) {
            return Math.floorDiv(now - origin, tenth);
        }

        /** The calls and retries counted before the mark of {@code tenth}: zero up to the first tenth. */
        private long[] mark(long markedTenth) {
            if (markedTenth <= 0) {
                return new long[] {0, 0};
            }
            for (long[] check : checks) {
                if (check[0] >= markedTenth) {
                    return new long[] {check[1], check[2]};
                }
            }
            throw new IllegalStateException("no mark for tenth " + markedTenth);
        }

        private boolean budget(long now) {
            long current = tenthOf(now);
            checks.add(new long[] {current, callTimes.size(), retryTimes.size()});
            long[] mark = mark(current - BUCKETS + 1);
            long calls = callTimes.size() - mark[0];
            long retries = retryTimes.size() - mark[1];
            if (retries + 1 > reserve + fraction * calls) {
                return false;
            }
            seen("budget granted");
            retryTimes.add(now);
            // The documented limit, from the actual times: the last ten tenths, this one included.
            long first = current - BUCKETS + 1;
            long recentCalls = callTimes.stream().filter(t -> tenthOf(t) >= first).count();
            long recentRetries = retryTimes.stream().filter(t -> tenthOf(t) >= first).count();
            lastGrant = recentRetries <= reserve + fraction * recentCalls ? ""
                    : recentRetries + " retries for " + recentCalls + " calls in the last ten tenths";
            return true;
        }

        /** {@code RetrySnapshot.Budget(calls, retries, allowed)} of the current window, or {@code null}. */
        RetrySnapshot.Budget snapshot(long now) {
            if (!budgeted) {
                return null;
            }
            long checked = checks.isEmpty() ? 0 : checks.getLast()[0];
            long oldest = Math.max(tenthOf(now), checked) - BUCKETS + 1;
            long calls = 0;
            long retries = 0;
            if (oldest <= checked) { // otherwise every mark is older than the window
                long[] mark = mark(oldest);
                calls = callTimes.size() - mark[0];
                retries = retryTimes.size() - mark[1];
            }
            return new RetrySnapshot.Budget(calls, retries, (long) (reserve + fraction * calls));
        }

        long toNextTenth(long now) {
            return (tenthOf(now) + 1) * tenth + origin - now;
        }
    }

    /** A clock moved by hand; sleeping moves it. Nothing is scheduled by synchronous retries. */
    private record TestTicker(ModelRuns.Clock clock) implements Ticker {
        @Override
        public long read() {
            return clock.now;
        }

        @Override
        public void sleep(long nanos) {
            clock.now += nanos;
        }

        @Override
        public Future<?> schedule(long delayNanos, Runnable task) {
            throw new UnsupportedOperationException("not used by synchronous retries");
        }

        @Override
        public void onAdvance(LongConsumer listener) {
            // nothing to tell: synchronous retries schedule nothing
        }
    }
}
