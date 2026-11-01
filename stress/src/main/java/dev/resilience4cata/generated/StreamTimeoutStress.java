package dev.resilience4cata.generated;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.TimeoutConfig;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * {@link TimeoutPolicy#executeStreamAsync} on a ticker: the idle deadline of a streaming call, which an
 * element moved, re-arms itself for the rest of the time while the stream ends. What must hold: the
 * re-check that loses to the end is cancelled, so nothing stays scheduled after the call (a manual
 * ticker's {@code pendingTasks()} must be exact), and the call ends with the stream's value. In package
 * {@code dev.resilience4cata.generated} for the environment of a {@link RecordingTicker}.
 */
public final class StreamTimeoutStress {

    private static final long SECOND = 1_000_000_000L;

    private StreamTimeoutStress() {}

    /**
     * Timeout 1 s; an element arrived at 0.5 s and the first deadline (1 s) is due: running it schedules
     * a re-check in 0.5 s, while the stream completes. Results: tasks still pending on the ticker, call
     * result (1 the value, 2 a timeout).
     */
    @JCStressTest
    @Outcome(id = "0, 1", expect = ACCEPTABLE, desc = "Nothing left scheduled; the stream's value.")
    @Outcome(id = "1, 1", expect = FORBIDDEN, desc = "A re-check stayed scheduled after the call ended.")
    @Outcome(id = ".*, 2", expect = FORBIDDEN, desc = "Timed out though an element arrived within the timeout.")
    @Outcome(expect = FORBIDDEN, desc = "Unexpected.")
    @State
    public static class RecheckVsEnd {
        final RecordingTicker ticker = new RecordingTicker();
        final TimeoutPolicy timeout = new TimeoutPolicy("stress",
                TimeoutConfig.builder().timeout(Duration.ofSeconds(1)).build(), PolicyEnvironment.of(ticker, null));
        final CompletableFuture<String> stage = new CompletableFuture<>();
        final ElementListener[] listener = new ElementListener[1];
        final CompletableFuture<String> result;

        public RecheckVsEnd() {
            result = timeout.executeStreamAsync(() -> stage, (each, everyElement) -> listener[0] = each);
            ticker.set(SECOND / 2);
            listener[0].element();
            ticker.set(SECOND); // the first deadline is due
        }

        @Actor
        public void firstDeadline() {
            ticker.scheduled.get(0).run();
        }

        @Actor
        public void end() {
            stage.complete("ok");
        }

        @Arbiter
        public void after(II_Result r) {
            r.r1 = ticker.pending();
            r.r2 = "ok".equals(result.getNow(null)) ? 1
                    : result.isCompletedExceptionally() && result.exceptionNow() instanceof CallTimeoutException ? 2 : 0;
        }
    }
}
