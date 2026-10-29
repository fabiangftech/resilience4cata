package dev.resilience4cata.reactor.generated;

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
 * <p>Each attempt is an {@link Attempt}, created by {@link #attempt} and handed to the layers inside the
 * retry or the fallback; they subscribe to the delegate's {@code Flux} through it, and the policies that
 * need the elements listen through it. Its elements go straight to the subscriber. The first element
 * <em>commits</em> the call: from then on no other attempt may emit, so a failure is the call's failure,
 * whatever the retry and the fallback would do. {@link #attempt} returns the boundary the retry and the
 * fallback see: a failure before the commit is one they act on; one after the commit reaches the
 * subscriber, and they see a success.
 *
 * <p>Attempts are numbered in the order they start, and {@code gate} holds the number of the one that may
 * still commit, or {@code COMMITTED}. The first element of attempt {@code n} commits by moving {@code n} to
 * {@code COMMITTED}; when attempt {@code n} fails, its boundary moves {@code n} to {@code n + 1}. Exactly
 * one of them wins. An attempt keeps its number, so one whose layers started it late (a bulkhead's hand-off
 * after its timeout fired, while the retry or the fallback already moved on) can never commit, and its
 * elements are discarded.
 *
 * <p>Demand: everything the subscriber requests is requested from the newest attempt that subscribed, and
 * a new attempt is asked for all of it (no attempt before the commit has emitted anything); an older
 * attempt that subscribes late gets nothing. Requests and the switch to a new attempt are serialized by a
 * drain loop, as Reactor's own multi-subscription operators do. Elements and the end are serialized by a
 * second, smaller one: elements come from one attempt at a time, so only the end (a timeout or a failure on
 * another thread) can race them.
 *
 * @param <T> the element type
 */
public final class FluxCall<T> implements Subscription {

    private static final VarHandle WIP;
    private static final VarHandle MISSED_REQUESTED;
    private static final VarHandle MISSED_SUBSCRIBER;
    private static final VarHandle GATE;
    private static final VarHandle EMITTER;
    private static final VarHandle TERMINAL;
    private static final VarHandle FUTURE;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            WIP = lookup.findVarHandle(FluxCall.class, "wip", int.class);
            MISSED_REQUESTED = lookup.findVarHandle(FluxCall.class, "missedRequested", long.class);
            MISSED_SUBSCRIBER = lookup.findVarHandle(FluxCall.class, "missedSubscriber", AttemptSubscriber.class);
            GATE = lookup.findVarHandle(FluxCall.class, "gate", int.class);
            EMITTER = lookup.findVarHandle(FluxCall.class, "emitter", Thread.class);
            TERMINAL = lookup.findVarHandle(FluxCall.class, "terminal", Object.class);
            FUTURE = lookup.findVarHandle(FluxCall.class, "future", CompletableFuture.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static final int COMMITTED = -1;
    static final Object COMPLETE = new Object();

    final CoreSubscriber<? super T> actual;

    // Demand. requested and current are touched only inside the drain loop.
    @SuppressWarnings("unused") // accessed through WIP
    private volatile int wip;
    @SuppressWarnings("unused") // accessed through MISSED_REQUESTED
    private volatile long missedRequested;
    @SuppressWarnings("unused") // accessed through MISSED_SUBSCRIBER
    private volatile @Nullable AttemptSubscriber<T> missedSubscriber;
    private long requested;
    private @Nullable AttemptSubscriber<T> current;
    private volatile boolean cancelled;

    /** The stage of an attempt that ended during its subscription: shared, as it only ever reads as done. */
    private static final CompletableFuture<Void> ENDED = CompletableFuture.completedFuture(null);

    // The attempt that may still commit, or COMMITTED.
    @SuppressWarnings("unused") // accessed through GATE
    private volatile int gate;

    // Elements against the end.
    @SuppressWarnings("unused") // accessed through EMITTER
    private volatile @Nullable Thread emitter; // the thread delivering an element, or null
    private boolean endDeferred; // the end arrived on the delivering thread, from inside onNext
    @SuppressWarnings("unused") // accessed through TERMINAL
    private volatile @Nullable Object terminal;

    @SuppressWarnings("unused") // accessed through FUTURE
    private volatile @Nullable CompletableFuture<Void> future;

    // A failure after the commit, kept for the subscriber while the policies above the boundary see a success.
    private volatile @Nullable Throwable committedFailure;

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

    /**
     * The layers of one attempt (generated code passes a lambda): given the attempt, they start it.
     *
     * @param <T> the element type
     */
    @FunctionalInterface
    public interface Layers<T> {

        /**
         * Runs the attempt's policies, ending with {@link Attempt#subscribe}.
         *
         * @param attempt this attempt
         * @return the stage of the attempt's stream
         * @throws Throwable if a layer fails before returning a stage
         */
        // java:S112: like AsyncCall, a layer may fail with anything
        @SuppressWarnings("java:S112")
        CompletionStage<Void> start(Attempt<T> attempt) throws Throwable;
    }

    /**
     * The boundary of one attempt (generated code wraps the layers inside the retry or the fallback in it,
     * or all the layers when there is neither): creates the attempt, runs {@code layers} with it, and
     * returns a stage that fails only when the attempt failed before the call committed. A failure after
     * the commit is kept for the subscriber and the stage succeeds, so no retry or fallback acts on it.
     * Cancelling the stage cancels the attempt.
     *
     * @param layers the layers inside the retry or fallback, ending with {@link Attempt#subscribe}
     * @return the stage the retry or fallback waits for
     */
    public CompletionStage<Void> attempt(Layers<T> layers) {
        Attempt<T> attempt = new Attempt<>(this, (int) GATE.getVolatile(this));
        CompletionStage<Void> stage;
        try {
            stage = layers.start(attempt);
        } catch (Throwable failure) {
            stage = CompletableFuture.failedFuture(failure);
        }
        if (stage == null) {
            stage = CompletableFuture.failedFuture(new NullPointerException("a layer returned no stage"));
        }
        if (stage.getClass() == CompletableFuture.class
                && ((CompletableFuture<Void>) stage).state() == java.util.concurrent.Future.State.SUCCESS) {
            return stage; // ended during its subscription: nothing for the boundary to decide
        }
        Boundary boundary = new Boundary(this, attempt.number, stage);
        stage.whenComplete(boundary);
        return boundary;
    }

    /**
     * The only attempt of a call without a retry or a fallback (generated code wraps all the layers in it):
     * creates the attempt and runs {@code layers} with it. No boundary is needed: nothing acts on the outcome
     * but the subscriber.
     *
     * @param layers the layers, ending with {@link Attempt#subscribe}
     * @return the stage of the attempt's stream
     */
    public CompletionStage<Void> single(Layers<T> layers) {
        try {
            CompletionStage<Void> stage = layers.start(new Attempt<>(this, 0));
            return stage != null ? stage : CompletableFuture.failedFuture(new NullPointerException("a layer returned no stage"));
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /**
     * Subscribes the fallback's {@code Flux}, as one more attempt (the fallback only runs before the call
     * committed, after the retry's last attempt failed).
     *
     * @param flux what the fallback returned
     * @return the stage of the fallback's stream
     */
    public CompletionStage<Void> recover(@Nullable Flux<? extends T> flux) {
        return new Attempt<>(this, (int) GATE.getVolatile(this)).subscribe(flux);
    }

    /**
     * The fallback's value, as a stream of that one element ({@code null}: an empty stream).
     *
     * @param value what the fallback returned
     * @return the stage of that stream
     */
    public CompletionStage<Void> recoverValue(@Nullable T value) {
        return recover(value == null ? Flux.empty() : Flux.just(value));
    }

    /**
     * One attempt of a {@link FluxCall}: the policies that need its elements listen through it
     * ({@link Elements}), and its layers subscribe through it to the delegate's {@code Flux}.
     *
     * @param <T> the element type
     */
    public static final class Attempt<T> implements Elements {
        private final FluxCall<T> call;
        final int number;
        // Registered by the policies around this attempt before it subscribes (on the thread that runs them, or
        // published to the subscribing thread by the hand-off between them, a bulkhead's queue).
        private @Nullable ElementListener every;
        private @Nullable ElementListener first;
        private @Nullable ElementListener second;

        Attempt(FluxCall<T> call, int number) {
            this.call = call;
            this.number = number;
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

        /**
         * Subscribes this attempt to the delegate's {@code Flux}, with the subscriber's context, and returns a
         * stage that ends when the attempt's stream ends. A {@code null} {@code Flux} is a failed attempt.
         *
         * @param flux what the delegate returned
         * @return the stage of the attempt's stream: {@code null} when it completes, or its error
         */
        public CompletionStage<Void> subscribe(@Nullable Flux<? extends T> flux) {
            if (flux == null) {
                return CompletableFuture.failedFuture(new NullPointerException(
                        "A method that returns a Flux returned null instead of a Flux"));
            }
            AttemptSubscriber<T> subscriber = new AttemptSubscriber<>(call, number, every, first, second);
            flux.subscribe(subscriber);
            if (subscriber.isDone() && !subscriber.isCompletedExceptionally()) {
                // Ended during subscribe (Flux.just with demand): a plain completed future lets the policies take
                // their already-complete shortcut. The policies only read it (a cancel of a done future does nothing).
                return ENDED;
            }
            return subscriber;
        }
    }

    // ---- the gate

    boolean commit(int attempt) {
        return attempt != COMMITTED && attempt == (int) GATE.getOpaque(this)
                && GATE.compareAndSet(this, attempt, COMMITTED);
    }

    /**
     * Attempt {@code attempt} failed: closes it for good, unless the call committed first; {@code true} if it
     * had. Attempts run one after another, so when one fails the gate holds its number or {@code COMMITTED}.
     */
    boolean closeOrCommitted(int attempt) {
        return (int) GATE.compareAndExchange(this, attempt, attempt + 1) == COMMITTED;
    }

    // ---- elements and the end

    /**
     * Delivers one element of an attempt with a timeout: its end may come from the timer's thread while an
     * element is being delivered. Without a timeout, the end only ever comes from the attempt's own stream
     * (every policy completes on the thread that completes the stage), so elements go as they are
     * ({@link #emitDirectly}). Elements come one at a time (from the committed attempt), so only the end can
     * race them: a store-buffering pair with volatile accesses
     * on both sides ({@code emitter} written then {@code terminal} read here; the reverse in
     * {@link #terminate}), so at least one side sees the other. An element that sees the end is discarded; an
     * end that sees an element being delivered on another thread waits for it, and one that arrives on the
     * delivering thread itself (from inside {@code onNext}) is delivered by it once {@code onNext} returns. Per
     * element: a volatile write, a volatile read and a release write, no read-modify-write
     * ({@code stlr}/{@code ldar} on ARM).
     */
    void emit(T element) {
        Thread me = Thread.currentThread();
        boolean nested = (Thread) EMITTER.getOpaque(this) == me; // an element emitted from inside onNext
        if (!nested) {
            EMITTER.setVolatile(this, me);
        }
        if (TERMINAL.getVolatile(this) != null) {
            if (!nested) {
                EMITTER.setRelease(this, null);
            }
            Operators.onDiscard(element, actual.currentContext()); // after the end: nowhere to go
            return;
        }
        actual.onNext(element);
        if (!nested) {
            EMITTER.setRelease(this, null);
            if (endDeferred) {
                deliverTerminal();
            }
        }
    }

    /** Delivers one element of an attempt without a timeout: nothing else can end the call meanwhile. */
    void emitDirectly(T element) {
        actual.onNext(element);
    }

    /**
     * Delivers the end, once. If an element is being delivered on another thread (the end of a timeout between
     * elements), waits until it is: a rare race, as long as one {@code onNext}. If it is being delivered on this
     * thread, the end goes after it.
     */
    void terminate(Object end) {
        if (!TERMINAL.compareAndSet(this, null, end)) {
            return;
        }
        Thread me = Thread.currentThread();
        Thread emitting = (Thread) EMITTER.getVolatile(this);
        if (emitting == me) {
            endDeferred = true; // only this thread reads it, after its onNext returns
            return;
        }
        while (emitting != null) {
            Thread.onSpinWait();
            emitting = (Thread) EMITTER.getVolatile(this);
        }
        deliverTerminal();
    }

    private void deliverTerminal() {
        if (cancelled) {
            return; // the subscriber cancelled: no signal
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
            // As Reactor's own operators do for a CoreSubscriber: reported, ignored. Any other subscriber is wrapped by
            // Reactor's StrictSubscriber, which signals rule 3.9's IllegalArgumentException itself (the TCK checks it).
            Operators.reportBadRequest(n);
            return;
        }
        if ((int) WIP.getOpaque(this) == 0 && WIP.compareAndSet(this, 0, 1)) {
            // Nobody drains: add the demand and forward it ourselves (two atomics, as Reactor's own fast path).
            requested = Operators.addCap(requested, n);
            AttemptSubscriber<T> now = current;
            if ((int) WIP.getAndAdd(this, -1) != 1) {
                drainLoop();
            }
            if (now != null) {
                now.upstream().request(n);
            }
            return;
        }
        long pending = (long) MISSED_REQUESTED.getVolatile(this);
        for (;;) {
            long witness = (long) MISSED_REQUESTED.compareAndExchange(this, pending, Operators.addCap(pending, n));
            if (witness == pending) {
                break;
            }
            pending = witness;
        }
        drain();
    }

    @Override
    public void cancel() {
        if (cancelled) {
            return;
        }
        cancelled = true;
        CompletableFuture<Void> started = (CompletableFuture<Void>) FUTURE.getVolatile(this);
        if (started != null) {
            started.cancel(true);
        }
    }

    boolean isCancelled() {
        return cancelled;
    }

    /**
     * An attempt subscribed: if it is newer than the current one, it becomes the one asked for elements, and
     * gets everything requested so far. After the subscriber cancelled, nothing: the attempt itself is
     * cancelled through the policies (its subscription must be cancelled once, by it).
     */
    void arbitrate(AttemptSubscriber<T> next) {
        if (cancelled) {
            return;
        }
        if ((int) WIP.getOpaque(this) == 0 && WIP.compareAndSet(this, 0, 1)) {
            // Nobody drains: switch to it ourselves if it is newer, and ask it for everything requested so far.
            AttemptSubscriber<T> now = current;
            boolean newer = now == null || next.number > now.number;
            long amount = requested;
            if (newer) {
                current = next;
            }
            if ((int) WIP.getAndAdd(this, -1) != 1) {
                drainLoop();
            }
            if (newer && amount != 0) {
                next.upstream().request(amount);
            }
            return;
        }
        AttemptSubscriber<T> pending = (AttemptSubscriber<T>) MISSED_SUBSCRIBER.getVolatile(this);
        for (;;) {
            if (pending != null && pending.number >= next.number) {
                break; // a newer attempt is waiting already: this one gets nothing
            }
            AttemptSubscriber<T> witness = (AttemptSubscriber<T>) MISSED_SUBSCRIBER.compareAndExchange(this, pending, next);
            if (witness == pending) {
                break;
            }
            pending = witness;
        }
        drain();
    }

    private void drain() {
        if ((int) WIP.getAndAdd(this, 1) != 0) {
            return;
        }
        drainLoop();
    }

    /** Runs with the drain's ownership taken (wip at least 1): applies what others left, until nothing is left. */
    private void drainLoop() {
        int missed = 1;
        long amount = 0;
        Subscription target = null;
        for (;;) {
            AttemptSubscriber<T> next = (AttemptSubscriber<T>) MISSED_SUBSCRIBER.getAndSet(this, null);
            long more = (long) MISSED_REQUESTED.getAndSet(this, 0L);
            if (more != 0) {
                requested = Operators.addCap(requested, more);
            }
            AttemptSubscriber<T> now = current;
            if (next != null && (now == null || next.number > now.number)) {
                current = next;
                target = next.upstream();
                amount = requested; // nothing was emitted before the commit, so the new attempt gets it all
            } else if (more != 0 && now != null) {
                Subscription upstream = now.upstream();
                if (target != upstream) {
                    target = upstream;
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
        private final int attempt;
        private final CompletionStage<Void> inner;
        private volatile boolean cancelling;

        Boundary(FluxCall<?> call, int attempt, CompletionStage<Void> inner) {
            this.call = call;
            this.attempt = attempt;
            this.inner = inner;
        }

        @Override
        public void accept(@Nullable Void ignored, @Nullable Throwable failure) {
            if (cancelling) {
                return;
            }
            if (failure == null) {
                super.complete(null);
            } else if (call.closeOrCommitted(attempt)) {
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
