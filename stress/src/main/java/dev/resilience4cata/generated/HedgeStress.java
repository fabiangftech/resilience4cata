package dev.resilience4cata.generated;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import dev.resilience4cata.HedgeConfig;
import dev.resilience4cata.event.EventListener;
import dev.resilience4cata.event.HedgeEvent;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * The hedge's winner race ({@link HedgePolicy}): attempts that complete at the same time, a launch racing
 * the call's settlement, and a cancel racing a win. What must hold: the call has exactly one outcome, a
 * hedge's win is published only when the hedge's value is the result, a failure never wins while another
 * attempt can still succeed, and no attempt is left running after the call settled. In package
 * {@code dev.resilience4cata.generated} for the package-private seams, like {@link TimeoutStress}.
 */
public final class HedgeStress {

    private HedgeStress() {}

    /** Each call is an attempt whose stage the actors complete. */
    static final class Attempts implements AsyncCall<String> {
        final List<CompletableFuture<String>> stages = new CopyOnWriteArrayList<>();

        @Override
        public CompletionStage<String> call() {
            CompletableFuture<String> stage = new CompletableFuture<>();
            stages.add(stage);
            return stage;
        }
    }

    /** A hedge with {@code maxAttempts} attempts, no budget, and a manual timer that never fires. */
    static HedgePolicy hedge(int maxAttempts, Duration delay, AtomicInteger won, AtomicInteger failed) {
        EventListener<HedgeEvent.Won> wins = event -> won.incrementAndGet();
        EventListener<HedgeEvent.Failed> failures = event -> failed.incrementAndGet();
        PolicyEnvironment environment = PolicyEnvironment.DEFAULT.withEvents(EventDispatcher.of(List.of(
                new EventDispatcher.Subscription(HedgeEvent.Won.class, null, wins),
                new EventDispatcher.Subscription(HedgeEvent.Failed.class, null, failures))));
        HedgeConfig config = HedgeConfig.builder().maxAttempts(maxAttempts).delay(delay).budgetPercent(0).build();
        return new HedgePolicy("h", config, environment, new TimeoutTimer(task -> { }));
    }

    /** 1 for "first", 2 for "second", -1 for a failure, 0 if the call has no outcome. */
    static int valueOf(CompletableFuture<String> result) {
        if (!result.isDone()) {
            return 0;
        }
        if (result.isCompletedExceptionally()) {
            return -1;
        }
        return result.join().equals("first") ? 1 : 2;
    }

    /**
     * Both attempts (in flight together, {@code delay = 0}) succeed at the same time. One wins; a {@code Won}
     * event is published if and only if the hedge (attempt 2) is the one whose value the call returns.
     */
    @JCStressTest
    @Outcome(id = "1, 0", expect = ACCEPTABLE, desc = "Attempt 1 won; no Won event.")
    @Outcome(id = "2, 1", expect = ACCEPTABLE, desc = "The hedge won, once.")
    @Outcome(expect = FORBIDDEN, desc = "No outcome, or a Won event that does not match the result.")
    @State
    public static class SuccessVsSuccess {
        final AtomicInteger won = new AtomicInteger();
        final Attempts attempts = new Attempts();
        final CompletableFuture<String> first = new CompletableFuture<>();
        final HedgePolicy.HedgeCall<String> call =
                hedge(2, Duration.ZERO, won, new AtomicInteger()).startForTest(attempts, first);

        @Actor
        public void firstSucceeds() {
            first.complete("first");
        }

        @Actor
        public void hedgeSucceeds() {
            attempts.stages.get(0).complete("second");
        }

        @Arbiter
        public void outcome(II_Result r) {
            r.r1 = valueOf(call.result);
            r.r2 = won.get();
        }
    }

    /** One attempt succeeds while the other fails: the failure never wins, whatever the order. */
    @JCStressTest
    @Outcome(id = "1, 0", expect = ACCEPTABLE, desc = "The success won.")
    @Outcome(expect = FORBIDDEN, desc = "The failure ended the call while an attempt could still succeed.")
    @State
    public static class SuccessVsFailure {
        final AtomicInteger failed = new AtomicInteger();
        final Attempts attempts = new Attempts();
        final CompletableFuture<String> first = new CompletableFuture<>();
        final HedgePolicy.HedgeCall<String> call =
                hedge(2, Duration.ZERO, new AtomicInteger(), failed).startForTest(attempts, first);

        @Actor
        public void firstSucceeds() {
            first.complete("first");
        }

        @Actor
        public void hedgeFails() {
            attempts.stages.get(0).completeExceptionally(new IOException("down"));
        }

        @Arbiter
        public void outcome(II_Result r) {
            r.r1 = valueOf(call.result);
            r.r2 = failed.get();
        }
    }

    /** Both attempts fail at the same time: the call fails exactly once, after the last of them. */
    @JCStressTest
    @Outcome(id = "-1, 1", expect = ACCEPTABLE, desc = "Failed once, after both failures were counted.")
    @Outcome(expect = FORBIDDEN, desc = "A lost failure (no outcome), or two Failed events.")
    @State
    public static class FailureVsFailure {
        final AtomicInteger failed = new AtomicInteger();
        final Attempts attempts = new Attempts();
        final CompletableFuture<String> first = new CompletableFuture<>();
        final HedgePolicy.HedgeCall<String> call =
                hedge(2, Duration.ZERO, new AtomicInteger(), failed).startForTest(attempts, first);

        @Actor
        public void firstFails() {
            first.completeExceptionally(new IOException("first"));
        }

        @Actor
        public void hedgeFails() {
            attempts.stages.get(0).completeExceptionally(new IOException("second"));
        }

        @Arbiter
        public void outcome(II_Result r) {
            r.r1 = valueOf(call.result);
            r.r2 = failed.get();
        }
    }

    /**
     * The delay fires (a launch) while attempt 1 succeeds (the settlement). Either the launch lost (no second
     * attempt), or the hedge started and was cancelled, by the settler or by its own launcher: it is never left
     * running. The call's value is always attempt 1's.
     */
    @JCStressTest
    @Outcome(id = "1, 0", expect = ACCEPTABLE, desc = "Settled first: no hedge started.")
    @Outcome(id = "2, 1", expect = ACCEPTABLE, desc = "The hedge started and was cancelled.")
    @Outcome(id = "2, 2", expect = FORBIDDEN, desc = "The hedge was left running after the call settled.")
    @Outcome(expect = FORBIDDEN, desc = "Anything else.")
    @State
    public static class LaunchVsSettle {
        final Attempts attempts = new Attempts();
        final CompletableFuture<String> first = new CompletableFuture<>();
        final HedgePolicy.HedgeCall<String> call = hedge(2, Duration.ofHours(1), new AtomicInteger(),
                new AtomicInteger()).startForTest(attempts, first);

        @Actor
        public void delayFires() {
            call.launch(HedgeEvent.Reason.DELAY, 1);
        }

        @Actor
        public void firstSucceeds() {
            first.complete("first");
        }

        @Arbiter
        public void outcome(II_Result r) {
            r.r1 = valueOf(call.result) == 1 ? 1 + attempts.stages.size() : -9;
            r.r2 = attempts.stages.isEmpty() ? 0 : attempts.stages.get(0).isCancelled() ? 1 : 2;
        }
    }

    /** The caller cancels while the hedge succeeds: one outcome, and attempt 1 is cancelled either way. */
    @JCStressTest
    @Outcome(id = "2, 1", expect = ACCEPTABLE, desc = "The hedge won; attempt 1 was cancelled as a loser.")
    @Outcome(id = "3, 1", expect = ACCEPTABLE, desc = "The cancel won; every attempt was cancelled.")
    @Outcome(expect = FORBIDDEN, desc = "Attempt 1 left running, or no outcome.")
    @State
    public static class CancelVsWin {
        final Attempts attempts = new Attempts();
        final CompletableFuture<String> first = new CompletableFuture<>();
        final HedgePolicy.HedgeCall<String> call =
                hedge(2, Duration.ZERO, new AtomicInteger(), new AtomicInteger()).startForTest(attempts, first);

        @Actor
        public void cancel() {
            call.result.cancel(true);
        }

        @Actor
        public void hedgeSucceeds() {
            attempts.stages.get(0).complete("second");
        }

        @Arbiter
        public void outcome(II_Result r) {
            r.r1 = call.result.isCancelled() ? 3 : valueOf(call.result);
            r.r2 = first.isCancelled() ? 1 : 0;
        }
    }
}
