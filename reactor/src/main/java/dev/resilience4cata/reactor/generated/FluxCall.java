package dev.resilience4cata.reactor.generated;

import dev.resilience4cata.generated.AsyncCall;
import dev.resilience4cata.generated.ElementListener;
import dev.resilience4cata.generated.Elements;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Operators;
import reactor.util.context.ContextView;

/**
 * One subscription to a generated method's {@code Flux}: the subscription its subscriber holds, and what
 * the generated code calls while the policies run (see {@link Fluxes}).
 *
 * <p>Each attempt is one subscription to the delegate's {@code Flux} ({@link #subscribe}), and its
 * elements go straight to the subscriber. The first element <em>commits</em> the call: from then on no
 * other attempt may start, so a failure is the call's failure, whatever the retry and the fallback would
 * do. {@link #attempt} is the boundary the retry and the fallback see: a failure before the commit is a
 * failure they act on; one after the commit reaches the subscriber, and they see a success.
 *
 * <p>Which attempt may commit is decided by one CAS on {@code gate}: it holds the number of the attempt
 * that may still commit, or {@code COMMITTED}. The first element of attempt {@code n} commits by moving
 * {@code n} to {@code COMMITTED}; the boundary, when attempt {@code n} fails, moves {@code n} to
 * {@code n + 1}. Exactly one of them wins, so an attempt that a timeout gave up on can never emit after
 * the retry started the next one.
 *
 * <p>Demand: everything the subscriber requests is requested from the current attempt, and a new attempt
 * is asked for all of it (no attempt before the commit has emitted anything). Requests and the switch to
 * a new attempt are serialized by a drain loop, as Reactor's own multi-subscription operators do.
 * Elements and the end are serialized by a second, smaller one: elements come from one attempt at a time,
 * so only the end (a timeout or a failure on another thread) can race them.
 *
 * @param <T> the element type
 */
public final class FluxCall<T> implements Elements, Subscription {

    private static final VarHandle WIP;
    private static final VarHandle MISSED_REQUESTED;
    private static final VarHandle MISSED_SUBSCRIPTION;
    private static final VarHandle GATE;
    private static final VarHandle EMITTING;
    private static final VarHandle TERMINAL;
    private static final VarHandle FUTURE;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            WIP = lookup.findVarHandle(FluxCall.class, "wip", int.class);
            MISSED_REQUESTED = lookup.findVarHandle(FluxCall.class, "missedRequested", long.class);
            MISSED_SUBSCRIPTION = lookup.findVarHandle(FluxCall.class, "missedSubscription", Subscription.class);
            GATE = lookup.findVarHandle(FluxCall.class, "gate", int.class);
            EMITTING = lookup.findVarHandle(FluxCall.class, "emitting", int.class);
            TERMINAL = lookup.findVarHandle(FluxCall.class, "terminal", Object.class);
            FUTURE = lookup.findVarHandle(FluxCall.class, "future", CompletableFuture.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static final int COMMITTED = -1;
    static final Object COMPLETE = new Object();

    final CoreSubscriber<? super T> actual;

    // Demand. requested and subscription are touched only inside the drain loop.
    @SuppressWarnings("unused") // accessed through WIP
    private volatile int wip;
    @SuppressWarnings("unused") // accessed through MISSED_REQUESTED
    private volatile long missedRequested;
    @SuppressWarnings("unused") // accessed through MISSED_SUBSCRIPTION
    private volatile @Nullable Subscription missedSubscription;
    private long requested;
    private @Nullable Subscription subscription;
    private volatile boolean cancelled;

    // The attempt that may still commit, or COMMITTED.
    @SuppressWarnings("unused") // accessed through GATE
    private volatile int gate;

    // Elements against the end.
    @SuppressWarnings("unused") // accessed through EMITTING
    private volatile int emitting;
    @SuppressWarnings("unused") // accessed through TERMINAL
    private volatile @Nullable Object terminal;

    @SuppressWarnings("unused") // accessed through FUTURE
    private volatile @Nullable CompletableFuture<Void> future;

    // A failure after the commit, kept for the subscriber while the policies above the boundary see a success.
    private volatile @Nullable Throwable committedFailure;

    // Listeners registered for the attempt that starts next. Attempts start one after another, each after the
    // policies around it registered (on the same thread, or published to the attempt's thread by the hand-off).
    private @Nullable ElementListener every;
    private @Nullable ElementListener first;
    private @Nullable ElementListener second;

    FluxCall(CoreSubscriber<? super T> actual) {
        this.actual = actual;
    }

    /**
     * The subscriber's context, which every attempt subscribes with.
     *
     * @return the context
     */
    public ContextView context() {
        return actual.currentContext();
    }

    @Override
    public void listen(ElementListener listener, boolean everyElement) {
        if (everyElement) {
            every = listener;
        } else if (first == null) {
            first = listener;
        } else {
            second = listener;
        }
    }

    private void clearListeners() {
        every = null;
        first = null;
        second = null;
    }

    /**
     * The boundary of one attempt, for the retry and the fallback (generated code passes it the layers
     * inside them): runs {@code next}, and returns a stage that fails only when the attempt failed before
     * the call committed. A failure after the commit is kept for the subscriber and the stage succeeds, so
     * no retry or fallback acts on it. Cancelling the stage cancels the attempt.
     *
     * @param next the layers inside the retry or fallback, ending with {@link #subscribe}
     * @return the stage the retry or fallback waits for
     */
    public CompletionStage<Void> attempt(AsyncCall<Void> next) {
        clearListeners();
        CompletionStage<Void> stage;
        try {
            stage = next.call();
        } catch (Throwable failure) {
            stage = CompletableFuture.failedFuture(failure);
        }
        if (stage == null) {
            stage = CompletableFuture.failedFuture(new NullPointerException("a layer returned no stage"));
        }
        Boundary boundary = new Boundary(this, stage);
        stage.whenComplete(boundary);
        return boundary;
    }

    /**
     * Subscribes one attempt to the delegate's {@code Flux}, with the subscriber's context, and returns a
     * stage that ends when the attempt's stream ends. The attempt takes the listeners registered since
     * the previous one. A {@code null} {@code Flux} is a failed attempt.
     *
     * @param flux what the delegate returned
     * @return the stage of the attempt's stream: {@code null} when it completes, or its error
     */
    public CompletionStage<Void> subscribe(@Nullable Flux<? extends T> flux) {
        if (flux == null) {
            clearListeners();
            return CompletableFuture.failedFuture(new NullPointerException(
                    "A method that returns a Flux returned null instead of a Flux"));
        }
        FluxAttempt<T> attempt = new FluxAttempt<>(this, (int) GATE.getVolatile(this), every, first, second);
        clearListeners();
        flux.subscribe(attempt);
        if (attempt.isDone() && !attempt.isCompletedExceptionally()) {
            // Ended during subscribe (Flux.just with demand): a plain completed future lets the policies take their
            // already-complete shortcut.
            return CompletableFuture.completedFuture(null);
        }
        return attempt;
    }

    /**
     * Subscribes the fallback's {@code Flux}, as one more attempt (the fallback only runs before the call
     * committed). Listeners left by an attempt that never subscribed are dropped first.
     *
     * @param flux what the fallback returned
     * @return the stage of the fallback's stream
     */
    public CompletionStage<Void> recover(@Nullable Flux<? extends T> flux) {
        clearListeners();
        return subscribe(flux);
    }

    // ---- the gate

    boolean commit(int attempt) {
        return attempt == (int) GATE.getOpaque(this) && GATE.compareAndSet(this, attempt, COMMITTED);
    }

    /** An attempt failed: closes it for good, unless it committed first; {@code true} if it had. */
    boolean closeOrCommitted() {
        int current = (int) GATE.getVolatile(this);
        while (current != COMMITTED) {
            int witness = (int) GATE.compareAndExchange(this, current, current + 1);
            if (witness == current) {
                return false;
            }
            current = witness;
        }
        return true;
    }

    // ---- elements and the end

    void emit(T element) {
        if (EMITTING.compareAndSet(this, 0, 1)) {
            actual.onNext(element);
            if (!EMITTING.compareAndSet(this, 1, 0)) {
                deliverTerminal(); // the end arrived while the element was delivered
            }
        } else {
            Operators.onDiscard(element, actual.currentContext()); // after the end: nowhere to go
        }
    }

    void terminate(Object end) {
        if (TERMINAL.compareAndSet(this, null, end) && (int) EMITTING.getAndAdd(this, 1) == 0) {
            deliverTerminal();
        }
    }

    private void deliverTerminal() {
        if (cancelled) {
            return;
        }
        Object end = TERMINAL.getVolatile(this);
        if (end == COMPLETE) {
            actual.onComplete();
        } else if (end instanceof Throwable failure) {
            actual.onError(failure);
        }
    }

    /** The call's future: subscribed once the policies started. */
    void start(CompletableFuture<Void> started) {
        FUTURE.setVolatile(this, started);
        if (cancelled) {
            started.cancel(true);
            return;
        }
        switch (started.state()) {
            case SUCCESS -> finish(null, null);
            case FAILED -> finish(null, started.exceptionNow());
            case CANCELLED -> finish(null, new CancellationException());
            case RUNNING -> started.whenComplete(this::finish);
        }
    }

    void fail(Throwable failure) {
        terminate(failure);
    }

    private void finish(@Nullable Void ignored, @Nullable Throwable failure) {
        if (cancelled) {
            return;
        }
        if (failure != null) {
            terminate(unwrap(failure));
        } else {
            Throwable late = committedFailure;
            terminate(late != null ? late : COMPLETE);
        }
    }

    static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException wrapped && wrapped.getCause() != null ? wrapped.getCause() : failure;
    }

    // ---- the subscriber's subscription

    @Override
    public void request(long n) {
        if (n <= 0) {
            // Rule 3.9: a non-positive request ends the call with an IllegalArgumentException; then the call is
            // cancelled like any other (one that has not started yet is cancelled when it starts).
            terminate(new IllegalArgumentException(
                    "§3.9 violated: positive request amount required but it was " + n));
            cancel();
            return;
        }
        long current = (long) MISSED_REQUESTED.getVolatile(this);
        for (;;) {
            long witness = (long) MISSED_REQUESTED.compareAndExchange(this, current, Operators.addCap(current, n));
            if (witness == current) {
                break;
            }
            current = witness;
        }
        drain();
    }

    @Override
    public void cancel() {
        if (cancelled) {
            return;
        }
        cancelled = true;
        cancelFuture();
    }

    private void cancelFuture() {
        CompletableFuture<Void> started = (CompletableFuture<Void>) FUTURE.getVolatile(this);
        if (started != null) {
            started.cancel(true);
        }
    }

    boolean isCancelled() {
        return cancelled;
    }

    /** A new attempt subscribed: it gets everything requested so far. */
    void arbitrate(Subscription next) {
        if (cancelled) {
            next.cancel();
            return;
        }
        MISSED_SUBSCRIPTION.setVolatile(this, next);
        drain();
    }

    private void drain() {
        if ((int) WIP.getAndAdd(this, 1) != 0) {
            return;
        }
        int missed = 1;
        long amount = 0;
        Subscription target = null;
        for (;;) {
            Subscription next = (Subscription) MISSED_SUBSCRIPTION.getAndSet(this, null);
            long more = (long) MISSED_REQUESTED.getAndSet(this, 0L);
            if (more != 0) {
                requested = Operators.addCap(requested, more);
            }
            if (next != null) {
                subscription = next;
                target = next;
                amount = requested; // nothing was emitted before the commit, so the new attempt gets it all
            } else if (more != 0 && subscription != null) {
                if (target != subscription) {
                    target = subscription;
                    amount = 0;
                }
                amount = Operators.addCap(amount, more);
            }
            missed = (int) WIP.getAndAdd(this, -missed) - missed;
            if (missed == 0) {
                break;
            }
        }
        if (target != null && amount != 0) {
            target.request(amount);
        }
    }

    /**
     * The stage the retry and the fallback see for one attempt: its outcome, except that a failure after
     * the commit is a success for them (the subscriber gets the failure). Cancelling it cancels the
     * attempt first, so that the policies inside let go before the retry's callbacks run.
     */
    private static final class Boundary extends CompletableFuture<Void> implements BiConsumer<Void, Throwable> {
        private final FluxCall<?> call;
        private final CompletionStage<Void> inner;
        private volatile boolean cancelling;

        Boundary(FluxCall<?> call, CompletionStage<Void> inner) {
            this.call = call;
            this.inner = inner;
        }

        @Override
        public void accept(@Nullable Void ignored, @Nullable Throwable failure) {
            if (cancelling) {
                return;
            }
            if (failure == null) {
                super.complete(null);
            } else if (call.closeOrCommitted()) {
                call.committedFailure = unwrap(failure);
                super.complete(null);
            } else {
                super.completeExceptionally(unwrap(failure));
            }
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (!isDone()) {
                cancelling = true;
                cancelInner();
            }
            return super.cancel(mayInterruptIfRunning);
        }

        @Override
        public boolean completeExceptionally(Throwable failure) {
            if (failure instanceof CancellationException && !isDone()) {
                cancelling = true;
                cancelInner();
            }
            return super.completeExceptionally(failure);
        }

        private void cancelInner() {
            try {
                inner.toCompletableFuture().cancel(true);
            } catch (UnsupportedOperationException refused) {
                // a minimal stage that cannot be cancelled: nothing to do
            }
        }
    }
}
