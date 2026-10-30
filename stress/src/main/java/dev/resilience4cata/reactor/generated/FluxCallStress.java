package dev.resilience4cata.reactor.generated;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.IIII_Result;
import org.openjdk.jcstress.infra.results.II_Result;
import org.openjdk.jcstress.infra.results.JJ_Result;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;

/**
 * {@link FluxCall} racing itself: the first element of an attempt against the boundary that closes the
 * attempt after it failed, an element against the end, and a request against a new attempt's
 * subscription. In the call's package for its package-private seams.
 */
public final class FluxCallStress {

    private FluxCallStress() {}

    /** A subscriber that records its signals and whether two of them ever overlapped. */
    static final class Recorder implements CoreSubscriber<String> {
        final AtomicInteger next = new AtomicInteger();
        final AtomicInteger ends = new AtomicInteger();
        final AtomicBoolean inside = new AtomicBoolean();
        volatile boolean overlapped;
        volatile boolean nextAfterEnd;

        @Override
        public void onSubscribe(Subscription subscription) {
            // not used: the tests drive the call directly
        }

        private void enter() {
            if (!inside.compareAndSet(false, true)) {
                overlapped = true;
            }
        }

        private void leave() {
            inside.set(false);
        }

        @Override
        public void onNext(String element) {
            enter();
            if (ends.get() > 0) {
                nextAfterEnd = true;
            }
            next.incrementAndGet();
            leave();
        }

        @Override
        public void onError(Throwable failure) {
            enter();
            ends.incrementAndGet();
            leave();
        }

        @Override
        public void onComplete() {
            enter();
            ends.incrementAndGet();
            leave();
        }
    }

    /** A subscription that adds up what is requested from it. */
    static final class Recording implements Subscription {
        final AtomicLong requested = new AtomicLong();

        @Override
        public void request(long n) {
            requested.addAndGet(n);
        }

        @Override
        public void cancel() {
            // not used here
        }
    }

    /**
     * Attempt 0's first element races the boundary closing attempt 0 after its failure (a timeout that
     * fired just before the element). Exactly one wins: the element commits and the boundary sees the
     * commit (the failure goes to the subscriber, no retry), or the boundary closes the attempt and the
     * element can never commit (the retry may start attempt 1).
     */
    @JCStressTest
    @Outcome(id = "1, 1", expect = ACCEPTABLE, desc = "The element committed; the boundary saw it.")
    @Outcome(id = "0, 0", expect = ACCEPTABLE, desc = "The boundary closed the attempt; the element is discarded.")
    @Outcome(id = "1, 0", expect = FORBIDDEN, desc = "The element committed, yet the retry may start another attempt.")
    @Outcome(id = "0, 1", expect = FORBIDDEN, desc = "Nothing committed, yet the boundary thought so.")
    @State
    public static class CommitVsClose {
        final FluxCall<String> call = new FluxCall<>(new Recorder());

        @Actor
        public void element(II_Result r) {
            r.r1 = call.commit(0) ? 1 : 0;
        }

        @Actor
        public void close(II_Result r) {
            r.r2 = call.closeOrCommitted(0) ? 1 : 0;
        }
    }

    /**
     * An element races the end (a timeout between elements, on another thread). The subscriber never sees
     * two signals at once, nor an element after the end; the end always arrives.
     */
    @JCStressTest
    @Outcome(id = "1, 1, 0, 0", expect = ACCEPTABLE, desc = "The element, then the end.")
    @Outcome(id = "0, 1, 0, 0", expect = ACCEPTABLE, desc = "The end first: the element is discarded.")
    @Outcome(id = ".*, .*, 1, .*", expect = FORBIDDEN, desc = "Two signals at once.")
    @Outcome(id = ".*, .*, .*, 1", expect = FORBIDDEN, desc = "An element after the end.")
    @Outcome(expect = FORBIDDEN, desc = "The end lost or repeated.")
    @State
    public static class ElementVsEnd {
        final Recorder subscriber = new Recorder();
        final FluxCall<String> call = new FluxCall<>(subscriber);

        @Actor
        public void element() {
            call.emit("a");
        }

        @Actor
        public void end() {
            call.terminate(FluxCall.COMPLETE);
        }

        @Arbiter
        public void count(IIII_Result r) {
            r.r1 = subscriber.next.get();
            r.r2 = subscriber.ends.get();
            r.r3 = subscriber.overlapped ? 1 : 0;
            r.r4 = subscriber.nextAfterEnd ? 1 : 0;
        }
    }

    /**
     * The subscriber requests 3 more while the retry's next attempt subscribes, after 2 were requested from
     * the attempt that failed. Nothing was emitted before the commit, so the new attempt must be asked for
     * exactly 5: never fewer (the stream would stall), never more (it could emit more than requested).
     */
    @JCStressTest
    @Outcome(id = "2, 5", expect = ACCEPTABLE, desc = "The request went to the new attempt.")
    @Outcome(id = "5, 5", expect = ACCEPTABLE, desc = "The request reached the old attempt too (it has ended).")
    @Outcome(expect = FORBIDDEN, desc = "The new attempt was asked for too little or too much.")
    @State
    public static class RequestVsNewAttempt {
        final FluxCall<String> call = new FluxCall<>(new Recorder());
        final Recording old = new Recording();
        final Recording fresh = new Recording();
        final AttemptSubscriber<String> oldAttempt = new AttemptSubscriber<>(call, 0, null, null, null);
        final AttemptSubscriber<String> freshAttempt = new AttemptSubscriber<>(call, 1, null, null, null);

        {
            oldAttempt.onSubscribe(old);
            call.request(2);
        }

        @Actor
        public void request() {
            call.request(3);
        }

        @Actor
        public void newAttempt() {
            freshAttempt.onSubscribe(fresh);
        }

        @Arbiter
        public void count(JJ_Result r) {
            r.r1 = old.requested.get();
            r.r2 = fresh.requested.get();
        }
    }

    /**
     * An attempt whose layers started it late (a bulkhead's hand-off after its timeout) subscribes while the
     * next attempt does, and the subscriber requests more. Whatever the order, only the newer attempt is asked for
     * elements, and for exactly what was requested; the late one gets nothing after its first request.
     */
    @JCStressTest
    @Outcome(id = "0, 5", expect = ACCEPTABLE, desc = "The newer attempt got everything.")
    @Outcome(id = "2, 5", expect = ACCEPTABLE, desc = "The late one subscribed first and got what was requested then.")
    @Outcome(id = "5, 5", expect = ACCEPTABLE, desc = "The late one got the later request too, before the newer one came.")
    @Outcome(id = "2, 3", expect = FORBIDDEN, desc = "The newer attempt lost the first request.")
    @Outcome(expect = FORBIDDEN, desc = "The newer attempt was asked for too little or too much.")
    @State
    public static class LateAttemptVsNewAttempt {
        final FluxCall<String> call = new FluxCall<>(new Recorder());
        final Recording late = new Recording();
        final Recording fresh = new Recording();
        final AttemptSubscriber<String> lateAttempt = new AttemptSubscriber<>(call, 0, null, null, null);
        final AttemptSubscriber<String> freshAttempt = new AttemptSubscriber<>(call, 1, null, null, null);

        {
            call.request(2);
        }

        @Actor
        public void late() {
            lateAttempt.onSubscribe(late);
        }

        @Actor
        public void fresh() {
            freshAttempt.onSubscribe(fresh);
        }

        @Actor
        public void request() {
            call.request(3);
        }

        @Arbiter
        public void count(JJ_Result r) {
            r.r1 = late.requested.get();
            r.r2 = fresh.requested.get();
        }
    }
}
