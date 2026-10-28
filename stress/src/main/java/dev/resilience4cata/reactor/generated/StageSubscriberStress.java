package dev.resilience4cata.reactor.generated;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import dev.resilience4cata.HedgeCancelledException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.III_Result;
import org.openjdk.jcstress.infra.results.II_Result;
import org.openjdk.jcstress.infra.results.I_Result;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * {@link StageSubscriber} racing the policies that cancel it (a timeout, a hedge, a cancelled caller)
 * with the {@code Mono} it subscribed to. What must hold: the subscription is cancelled exactly once and
 * never asked for anything after the cancel, and a value is either the result or handed to the discard
 * hook, never lost. In the subscriber's package for its package-private constructor.
 */
public final class StageSubscriberStress {

    private StageSubscriberStress() {}

    /** A subscription that counts what is asked of it. */
    static final class Recording implements Subscription {
        final AtomicLong requested = new AtomicLong();
        final AtomicInteger cancels = new AtomicInteger();

        @Override
        public void request(long n) {
            requested.addAndGet(n);
        }

        @Override
        public void cancel() {
            cancels.incrementAndGet();
        }
    }

    /** The context that {@code doOnDiscard} gives the operators above it, counting into {@code discards}. */
    static Context discardContext(AtomicInteger discards) {
        Context[] captured = new Context[1];
        Mono.deferContextual(view -> {
            captured[0] = Context.of(view);
            return Mono.empty();
        }).doOnDiscard(Object.class, value -> discards.incrementAndGet()).block();
        return captured[0];
    }

    /**
     * The cancel races the subscription's arrival. Either way the subscription is cancelled exactly once;
     * if the cancel came first, nothing is requested from it.
     */
    @JCStressTest
    @Outcome(id = "1, 1", expect = ACCEPTABLE, desc = "Subscribed, then cancelled.")
    @Outcome(id = "1, 0", expect = ACCEPTABLE, desc = "Cancelled first: the subscription is cancelled on arrival.")
    @Outcome(id = "0, .*", expect = FORBIDDEN, desc = "The subscription was never cancelled: the Mono runs on.")
    @Outcome(expect = FORBIDDEN, desc = "Cancelled twice.")
    @State
    public static class CancelVsSubscribe {
        final StageSubscriber<String> subscriber = new StageSubscriber<>(Context.empty());
        final Recording subscription = new Recording();

        @Actor
        public void cancel() {
            subscriber.cancel(true);
        }

        @Actor
        public void subscribe() {
            subscriber.onSubscribe(subscription);
        }

        @Arbiter
        public void count(II_Result r) {
            r.r1 = subscription.cancels.get();
            r.r2 = subscription.requested.get() > 0 ? 1 : 0;
        }
    }

    /**
     * A timeout's cancel races the value. Either the value won (the result, nothing cancelled or
     * discarded), or the cancel won (cancelled once, the value discarded).
     */
    @JCStressTest
    @Outcome(id = "0, 0, 0", expect = ACCEPTABLE, desc = "The value won.")
    @Outcome(id = "1, 1, 1", expect = ACCEPTABLE, desc = "The cancel won: the value went to the discard hook.")
    @Outcome(id = "1, 0, .*", expect = FORBIDDEN, desc = "The value was lost.")
    @Outcome(expect = FORBIDDEN, desc = "Anything else.")
    @State
    public static class CancelVsValue {
        final AtomicInteger discards = new AtomicInteger();
        final StageSubscriber<String> subscriber = new StageSubscriber<>(discardContext(discards));
        final Recording subscription = new Recording();

        {
            subscriber.onSubscribe(subscription);
        }

        @Actor
        public void cancel() {
            subscriber.cancel(true);
        }

        @Actor
        public void value() {
            subscriber.onNext("v");
        }

        @Arbiter
        public void count(III_Result r) {
            r.r1 = subscriber.isCancelled() ? 1 : 0;
            r.r2 = discards.get();
            r.r3 = subscription.cancels.get();
        }
    }

    /**
     * A hedge cancelling a losing attempt ({@code completeExceptionally} with a cancellation) races a
     * caller's {@code cancel}: the subscription is cancelled exactly once.
     */
    @JCStressTest
    @Outcome(id = "1", expect = ACCEPTABLE, desc = "Cancelled once.")
    @Outcome(expect = FORBIDDEN, desc = "Not cancelled, or cancelled twice.")
    @State
    public static class HedgeCancelVsCancel {
        final StageSubscriber<String> subscriber = new StageSubscriber<>(Context.empty());
        final Recording subscription = new Recording();

        {
            subscriber.onSubscribe(subscription);
        }

        @Actor
        public void hedge() {
            subscriber.completeExceptionally(new HedgeCancelledException("h"));
        }

        @Actor
        public void cancel() {
            subscriber.cancel(true);
        }

        @Arbiter
        public void count(I_Result r) {
            r.r1 = subscription.cancels.get();
        }
    }
}
