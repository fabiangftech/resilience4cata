package dev.resilience4cata.generated;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import dev.resilience4cata.RateLimiterConfig;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.III_Result;

/**
 * {@link RateLimiterPolicy#executeAsync}: a call that reserved a later permit waits for it on the
 * registry's ticker ({@code DelayedStart}). When it is due, the start races the caller's cancel. What
 * must hold: exactly one wins, so either the call starts and keeps its permit, or it never starts and
 * the permit is given back. In package {@code dev.resilience4cata.generated} for the environment of a
 * {@link RecordingTicker}.
 */
public final class DelayedStartStress {

    private static final long SECOND = 1_000_000_000L;

    private DelayedStartStress() {}

    /**
     * One permit per second, {@code maxWait = 2s}; the burst is used in the setup, so the call reserves
     * the permit due in 1 s. Results: calls started, the returned future cancelled (1), and how many
     * seconds away the arbiter's {@code reserve()} books the next permit (1 if the permit was given back,
     * 2 if it is still used).
     */
    @JCStressTest
    @Outcome(id = "0, 1, 1", expect = ACCEPTABLE, desc = "Cancelled before it started; the permit went back.")
    @Outcome(id = "1, 1, 2", expect = ACCEPTABLE, desc = "Started, then the cancel reached its stage; the permit is used.")
    @Outcome(id = "1, .*, 1", expect = FORBIDDEN, desc = "Started and gave its permit back.")
    @Outcome(id = "0, .*, 2", expect = FORBIDDEN, desc = "Never started and kept its permit.")
    @Outcome(expect = FORBIDDEN, desc = "Unexpected.")
    @State
    public static class CancelVsStart {
        final RecordingTicker ticker = new RecordingTicker();
        final RateLimiterPolicy limiter = new RateLimiterPolicy("stress",
                RateLimiterConfig.builder().permits(1).period(Duration.ofSeconds(1)).maxWait(Duration.ofSeconds(2)).build(),
                PolicyEnvironment.of(ticker, null));
        final AtomicInteger started = new AtomicInteger();
        final CompletableFuture<String> stage = new CompletableFuture<>();
        final CompletableFuture<String> result;

        public CancelVsStart() {
            if (!limiter.tryAcquire()) {
                throw new IllegalStateException("the burst must be available");
            }
            result = limiter.executeAsync(() -> {
                started.incrementAndGet();
                return stage;
            });
            if (ticker.scheduled.size() != 1) {
                throw new IllegalStateException("the call must wait for its permit");
            }
        }

        @Actor
        public void due() {
            ticker.scheduled.get(0).run();
        }

        @Actor
        public void cancel() {
            result.cancel(true);
        }

        @Arbiter
        public void after(III_Result r) {
            r.r1 = started.get();
            r.r2 = result.isCancelled() ? 1 : 0;
            long wait = limiter.reserve();
            r.r3 = wait < 0 ? -1 : (int) ((wait + SECOND - 1) / SECOND);
        }
    }
}
