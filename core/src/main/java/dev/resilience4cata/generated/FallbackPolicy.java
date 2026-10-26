package dev.resilience4cata.generated;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.event.FallbackEvent;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.Nullable;

/**
 * Fallback for generated code: decides which failures get an alternative result, and builds the
 * {@link FallbackContext} for typed handlers. Everything here runs only after a call has failed.
 *
 * <pre>{@code
 * try {
 *     return delegate.call(p0);
 * } catch (Throwable e) {
 *     if (!fallback.handles(e)) throw e;
 *     return delegate.callFallback(p0, e);           // or handler.apply(fallback.context(e, 1, args))
 * }
 * }</pre>
 */
public final class FallbackPolicy {

    private final String instanceId;
    private final List<Class<? extends Throwable>> applyOn;
    private final List<Class<? extends Throwable>> skipOn;
    private final @Nullable List<Class<? extends Throwable>> taken;
    private final Classification classification = new Classification();
    private final @Nullable EventSink<FallbackEvent.Applied> applied;
    private final java.util.function.LongSupplier ticker;

    /**
     * Creates the policy for one method.
     *
     * @param instanceId id of the method, {@code <interface>#<method>(<parameter types>)}
     * @param applyOn failures that get the fallback
     * @param skipOn failures that never get the fallback; wins over {@code applyOn}
     */
    public FallbackPolicy(String instanceId, List<Class<? extends Throwable>> applyOn,
            List<Class<? extends Throwable>> skipOn) {
        this(instanceId, applyOn, skipOn, PolicyEnvironment.DEFAULT);
    }

    /**
     * Creates the policy for one method, publishing {@link FallbackEvent}s to the environment's listeners.
     *
     * @param instanceId id of the method, {@code <interface>#<method>(<parameter types>)}
     * @param applyOn failures that get the fallback
     * @param skipOn failures that never get the fallback; wins over {@code applyOn}
     * @param environment the clock and the event listeners
     */
    public FallbackPolicy(String instanceId, List<Class<? extends Throwable>> applyOn,
            List<Class<? extends Throwable>> skipOn, PolicyEnvironment environment) {
        this(instanceId, applyOn, skipOn, null, environment);
    }

    /**
     * Creates the policy for a method whose sibling fallback methods do not take every failure: a failure
     * that is not an instance of one of {@code taken} is rethrown, so this policy does not handle it either.
     *
     * @param instanceId id of the method, {@code <interface>#<method>(<parameter types>)}
     * @param applyOn failures that get the fallback
     * @param skipOn failures that never get the fallback; wins over {@code applyOn}
     * @param taken the exception types of the sibling fallback methods, or {@code null} if a fallback
     *     takes every failure (a handler, or a sibling that takes {@code Throwable} or no exception)
     * @param environment the clock and the event listeners
     */
    public FallbackPolicy(String instanceId, List<Class<? extends Throwable>> applyOn,
            List<Class<? extends Throwable>> skipOn, @Nullable List<Class<? extends Throwable>> taken,
            PolicyEnvironment environment) {
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.applyOn = List.copyOf(applyOn);
        this.skipOn = List.copyOf(skipOn);
        this.taken = taken == null ? null : List.copyOf(taken);
        this.applied = environment.events.sink(FallbackEvent.Applied.class, instanceId);
        this.ticker = environment.ticker;
    }

    /** Returns the method's id. */
    public String instanceId() {
        return instanceId;
    }

    /**
     * Whether the fallback applies to a failure: it is an instance of an {@code applyOn} type, of no
     * {@code skipOn} type and, with sibling fallback methods that do not take every failure, of the
     * exception type of one of them. Cached per exception class. Generated code calls it once per failure
     * and runs a fallback exactly when it returns {@code true}, so this is where
     * {@link FallbackEvent.Applied} is published: only for failures that a fallback really handles.
     *
     * @param failure the failure
     * @return {@code true} if the fallback must run
     */
    public boolean handles(Throwable failure) {
        boolean handled = classification.get(failure.getClass());
        EventSink<FallbackEvent.Applied> sink = applied;
        if (handled && sink != null) {
            sink.publish(new FallbackEvent.Applied(instanceId, ticker.getAsLong(), failure));
        }
        return handled;
    }

    /**
     * Builds the context passed to a typed handler.
     *
     * @param failure what failed
     * @param attempts number of attempts made, including the first one
     * @param arguments the arguments of the original call, primitives boxed
     * @return the context
     */
    public FallbackContext context(Throwable failure, int attempts, @Nullable Object[] arguments) {
        return new Context(instanceId, failure, attempts, arguments);
    }

    /**
     * Turns a failure thrown by a typed handler into something a method that does not declare it can
     * throw. Generated code rethrows the method's declared checked exceptions itself before calling this.
     *
     * @param failure what the handler threw
     * @return the failure itself if it is unchecked, otherwise an {@link UndeclaredThrowableException} wrapping it
     * @throws Error if the failure is an {@code Error}, which is rethrown as is
     */
    public static RuntimeException unchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new UndeclaredThrowableException(failure,
                "The fallback threw a checked exception that the method does not declare: " + failure);
    }

    private final class Classification extends ClassValue<Boolean> {
        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<? extends Throwable> skipped : skipOn) {
                if (skipped.isAssignableFrom(type)) {
                    return false;
                }
            }
            for (Class<? extends Throwable> applies : applyOn) {
                if (applies.isAssignableFrom(type)) {
                    return taken == null || takes(type);
                }
            }
            return false;
        }

        private boolean takes(Class<?> type) {
            for (Class<? extends Throwable> parameter : Objects.requireNonNull(taken)) {
                if (parameter.isAssignableFrom(type)) {
                    return true;
                }
            }
            return false;
        }
    }

    @SuppressWarnings("java:S6218") // one per failure, handed to a fallback; never compared or used as a key
    private record Context(String instanceId, Throwable failure, int attempts, @Nullable Object[] arguments)
            implements FallbackContext {

        @Override
        public int argumentCount() {
            return arguments.length;
        }

        @Override
        public <A> @Nullable A argument(int index, Class<A> type) {
            Objects.checkIndex(index, arguments.length);
            Object value = arguments[index];
            return value == null ? null : wrapper(type).cast(value);
        }

        @SuppressWarnings("unchecked")
        private static <A> Class<A> wrapper(Class<A> type) {
            if (!type.isPrimitive()) {
                return type;
            }
            return (Class<A>) switch (type.getName()) {
                case "int" -> Integer.class;
                case "long" -> Long.class;
                case "boolean" -> Boolean.class;
                case "double" -> Double.class;
                case "float" -> Float.class;
                case "char" -> Character.class;
                case "byte" -> Byte.class;
                case "short" -> Short.class;
                default -> type;
            };
        }

        @Override
        public String toString() {
            return "FallbackContext[" + instanceId + ", attempts=" + attempts + ", failure=" + failure + "]";
        }
    }

    /**
     * Runs an asynchronous call and, if it fails with a failure this policy {@link #handles handles},
     * completes with the recovery's result instead. Used when the method has no retry; with a retry, the
     * recovery is passed to {@link RetryPolicy#executeAsync(AsyncCall, FallbackPolicy, Recovery)}.
     * Cancelling the returned future cancels the call's stage (or the recovery's), and the recovery
     * does not run.
     *
     * @param call the next layer
     * @param recovery the generated fallback
     * @param <T> the result type
     * @return a future that completes with the call's or the fallback's outcome
     */
    public <T> CompletableFuture<T> executeAsync(AsyncCall<T> call, Recovery<T> recovery) {
        CompletionStage<T> stage = AsyncSupport.call(call);
        LinkedFuture<T> result = new LinkedFuture<>(stage);
        stage.whenComplete((value, failure) -> {
            if (failure == null) {
                result.complete(value);
            } else {
                recoverOrFail(AsyncSupport.unwrap(failure), 1, recovery, result);
            }
        });
        return result;
    }

    /**
     * Completes {@code result} with the recovery's outcome if this policy handles the failure, else with the
     * failure. Does nothing if the caller cancelled {@code result}: nobody waits for an alternative result, so
     * the fallback does not run (and publishes no event).
     */
    // java:S2583: user code can return null despite the contract; this check turns it into a clear failure
    @SuppressWarnings("java:S2583")
    <T> void recoverOrFail(Throwable failure, int attempts, Recovery<T> recovery, LinkedFuture<T> result) {
        if (result.cancelling()) {
            return;
        }
        if (!handles(failure)) {
            result.fail(failure);
            return;
        }
        @Nullable CompletionStage<T> stage; // user code may return null: checked below
        try {
            stage = recovery.recover(failure, attempts);
        } catch (Throwable fallbackFailure) {
            result.fail(fallbackFailure);
            return;
        }
        if (stage == null) {
            result.fail(new NullPointerException("The fallback of " + instanceId + " returned null"));
            return;
        }
        result.link(stage); // a cancel from now on cancels the fallback's stage
        AsyncSupport.relay(stage, result);
    }
}
