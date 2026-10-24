package dev.resilience4cata.generated;

import dev.resilience4cata.FallbackContext;
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
    private final Classification classification = new Classification();

    /**
     * Creates the policy for one method.
     *
     * @param instanceId id of the method, {@code <interface>#<method>(<parameter types>)}
     * @param applyOn failures that get the fallback
     * @param skipOn failures that never get the fallback; wins over {@code applyOn}
     */
    public FallbackPolicy(String instanceId, List<Class<? extends Throwable>> applyOn,
            List<Class<? extends Throwable>> skipOn) {
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.applyOn = List.copyOf(applyOn);
        this.skipOn = List.copyOf(skipOn);
    }

    /** Returns the method's id. */
    public String instanceId() {
        return instanceId;
    }

    /**
     * Whether the fallback applies to a failure: it is an instance of an {@code applyOn} type and of no
     * {@code skipOn} type. Cached per exception class.
     *
     * @param failure the failure
     * @return {@code true} if the fallback must run
     */
    public boolean handles(Throwable failure) {
        return classification.get(failure.getClass());
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
            for (Class<? extends Throwable> applied : applyOn) {
                if (applied.isAssignableFrom(type)) {
                    return true;
                }
            }
            return false;
        }
    }

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
     *
     * @param call the next layer
     * @param recovery the generated fallback
     * @param <T> the result type
     * @return a future that completes with the call's or the fallback's outcome
     */
    public <T> CompletableFuture<T> executeAsync(AsyncCall<T> call, Recovery<T> recovery) {
        CompletableFuture<T> result = new CompletableFuture<>();
        AsyncSupport.call(call).whenComplete((value, failure) -> {
            if (failure == null) {
                result.complete(value);
            } else {
                recoverOrFail(AsyncSupport.unwrap(failure), 1, recovery, result);
            }
        });
        return result;
    }

    /** Completes {@code result} with the recovery's outcome if this policy handles the failure, else with the failure. */
    <T> void recoverOrFail(Throwable failure, int attempts, Recovery<T> recovery, CompletableFuture<T> result) {
        if (!handles(failure)) {
            result.completeExceptionally(failure);
            return;
        }
        CompletionStage<T> stage;
        try {
            stage = recovery.recover(failure, attempts);
        } catch (Throwable fallbackFailure) {
            result.completeExceptionally(fallbackFailure);
            return;
        }
        if (stage == null) {
            result.completeExceptionally(new NullPointerException("The fallback of " + instanceId + " returned null"));
            return;
        }
        AsyncSupport.relay(stage, result);
    }
}
