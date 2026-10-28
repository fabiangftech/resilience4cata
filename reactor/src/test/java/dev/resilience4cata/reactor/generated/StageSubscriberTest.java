package dev.resilience4cata.reactor.generated;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.HedgeCancelledException;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * {@link StageSubscriber} signal by signal, in every order that matters: the subscription arriving
 * before or after the cancel, values and errors arriving after the future is done.
 */
class StageSubscriberTest {

    /** A subscription that records what is asked of it. */
    private static final class Recording implements Subscription {
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

    private final StageSubscriber<String> subscriber = new StageSubscriber<>(Context.empty());
    private final Recording subscription = new Recording();

    @AfterEach
    void resetHooks() {
        Hooks.resetOnErrorDropped();
    }

    @Test
    void requestsEverythingOnSubscribe() {
        subscriber.onSubscribe(subscription);
        assertThat(subscription.requested).hasValue(Long.MAX_VALUE);
        assertThat(subscription.cancels).hasValue(0);
    }

    @Test
    void completesWithTheValueThenIgnoresTheCompletion() {
        subscriber.onSubscribe(subscription);
        subscriber.onNext("v");
        subscriber.onComplete();
        assertThat(subscriber).isCompletedWithValue("v");
    }

    @Test
    void completesWithNullWhenEmpty() {
        subscriber.onSubscribe(subscription);
        subscriber.onComplete();
        assertThat(subscriber).isCompletedWithValue(null);
    }

    @Test
    void completesWithTheError() {
        IOException failure = new IOException("x");
        subscriber.onSubscribe(subscription);
        subscriber.onError(failure);
        assertThat(subscriber).isCompletedExceptionally();
        assertThat(subscriber.exceptionNow()).isSameAs(failure);
        assertThat(subscription.cancels).as("a terminated subscription is not cancelled").hasValue(0);
    }

    @Test
    void anErrorThatIsACancellationFromUpstreamDoesNotCancelUpstream() {
        subscriber.onSubscribe(subscription);
        subscriber.onError(new CancellationException("from upstream"));
        assertThat(subscriber).isCancelled();
        assertThat(subscription.cancels).hasValue(0);
    }

    @Test
    void cancellingAfterSubscribingCancelsTheSubscriptionOnce() {
        subscriber.onSubscribe(subscription);
        assertThat(subscriber.cancel(true)).isTrue();
        assertThat(subscriber.cancel(true)).as("still cancelled, as CompletableFuture says").isTrue();
        assertThat(subscription.cancels).as("cancelled upstream only once").hasValue(1);
        assertThat(subscriber.upstreamCancelledForTest()).isTrue();
    }

    @Test
    void cancellingBeforeSubscribingCancelsTheSubscriptionWhenItArrives() {
        assertThat(subscriber.cancel(true)).isTrue();
        assertThat(subscriber.upstreamCancelledForTest()).isTrue();
        subscriber.onSubscribe(subscription);
        assertThat(subscription.cancels).hasValue(1);
        assertThat(subscription.requested).as("nothing requested from a cancelled subscription").hasValue(0);
    }

    @Test
    void aCancellationExceptionCancelsUpstreamLikeCancel() {
        subscriber.onSubscribe(subscription);
        assertThat(subscriber.completeExceptionally(new HedgeCancelledException("hedge"))).isTrue();
        assertThat(subscription.cancels).hasValue(1);
    }

    @Test
    void anyOtherExternalFailureLeavesUpstreamAlone() {
        subscriber.onSubscribe(subscription);
        subscriber.completeExceptionally(new IOException("not a cancel"));
        assertThat(subscription.cancels).hasValue(0);
    }

    @Test
    void cancellingADoneFutureDoesNothing() {
        subscriber.onSubscribe(subscription);
        subscriber.onNext("v");
        assertThat(subscriber.cancel(true)).isFalse();
        assertThat(subscriber.completeExceptionally(new CancellationException())).isFalse();
        assertThat(subscription.cancels).hasValue(0);
        assertThat(subscriber).isCompletedWithValue("v");
    }

    @Test
    void aSecondSubscriptionIsCancelled() {
        Recording second = new Recording();
        subscriber.onSubscribe(subscription);
        subscriber.onSubscribe(second);
        assertThat(second.cancels).hasValue(1);
        assertThat(second.requested).hasValue(0);
        assertThat(subscription.cancels).hasValue(0);
    }

    @Test
    void aValueAfterTheCancelGoesToTheDiscardHook() {
        // An upstream that ignores the cancel and emits anyway: the value may hold a resource (a buffer).
        List<Object> discarded = new CopyOnWriteArrayList<>();
        StageSubscriber<String> withHook = new StageSubscriber<>(discardContext(discarded));
        withHook.onSubscribe(subscription);
        withHook.cancel(true);
        withHook.onNext("late");
        assertThat(discarded).containsExactly("late");
    }

    @Test
    void anErrorAfterTheCancelGoesToTheErrorDroppedHook() {
        List<Throwable> dropped = new CopyOnWriteArrayList<>();
        Hooks.onErrorDropped(dropped::add);
        IOException late = new IOException("late");
        subscriber.onSubscribe(subscription);
        subscriber.cancel(true);
        subscriber.onError(late);
        assertThat(dropped).containsExactly(late);
    }

    @Test
    void aSecondValueIsDiscarded() {
        List<Object> discarded = new CopyOnWriteArrayList<>();
        StageSubscriber<String> withHook = new StageSubscriber<>(discardContext(discarded));
        withHook.onSubscribe(subscription);
        withHook.onNext("first");
        withHook.onNext("second");
        assertThat(withHook).isCompletedWithValue("first");
        assertThat(discarded).containsExactly("second");
    }

    /** The context that {@code doOnDiscard(Object.class, sink)} gives the operators above it. */
    private static Context discardContext(List<Object> discarded) {
        Context[] captured = new Context[1];
        Mono.deferContextual(view -> {
            captured[0] = Context.of(view);
            return Mono.empty();
        }).doOnDiscard(Object.class, discarded::add).block();
        return captured[0];
    }
}
