package dev.resilience4cata;

import dev.resilience4cata.generated.AsyncCall;
import dev.resilience4cata.generated.AttemptLayers;
import dev.resilience4cata.generated.AdaptiveLimitPolicy;
import dev.resilience4cata.generated.BulkheadPolicy;
import dev.resilience4cata.generated.CircuitBreakerPolicy;
import dev.resilience4cata.generated.ClassifierFailure;
import dev.resilience4cata.generated.FallbackPolicy;
import dev.resilience4cata.generated.HedgePolicy;
import dev.resilience4cata.generated.RateLimiterPolicy;
import dev.resilience4cata.generated.Recovery;
import dev.resilience4cata.generated.RetryPolicy;
import dev.resilience4cata.generated.StreamAttempt;
import dev.resilience4cata.generated.StreamCall;
import dev.resilience4cata.generated.TimeoutPolicy;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Resilience policies for code that cannot be annotated: third-party classes, lambdas, scripts. A
 * pipeline is built once from a {@link ResilienceRegistry}, is immutable and thread-safe, and is reused
 * for every call:
 *
 * <pre>{@code
 * Pipeline<Receipt> charge = Pipeline.builder(registry, "payments", Receipt.class)
 *         .retry("external")                                   // a named config
 *         .circuitBreaker(cb -> cb.failureRateThreshold(25))   // the defaults with overrides
 *         .timeout(t -> t.timeout(Duration.ofSeconds(2)))
 *         .fallback(failure -> Receipt.pending())
 *         .build();
 *
 * Receipt receipt = charge.execute(() -> client.charge(order));
 * CompletableFuture<Receipt> later = charge.executeAsync(() -> client.chargeAsync(order));
 * charge.run(() -> client.cancel(order));                  // a call with no result
 * }</pre>
 *
 * <p>Every policy is optional. They nest in the same order as in generated code, outermost first:
 * fallback, retry or hedge, circuit breaker, rate limiter, adaptive limit, timeout, bulkhead, then the call. The order in
 * which the builder's methods are called does not matter. A hedge ({@link Builder#hedge()}) only works with
 * {@link #executeAsync}, and cannot be combined with a retry. A {@link Classifier} can decide what the retry
 * retries and what the breaker records, results included ({@link Builder#classifier}).
 *
 * <p><b>Instances.</b> The pipeline's id plays the part of the {@code <interface>#<method>} id of
 * annotated methods: its policies are created by the registry under that id, so that
 * {@code registry.circuitBreaker("payments")} finds the breaker, and two pipelines (or a pipeline and an
 * annotation with {@code name = "payments"}) with the same id and configuration share their circuit
 * breaker, rate limiter, adaptive limit, bulkhead and retry instances. The same id with a different configuration is an
 * error, as for annotations.
 *
 * <p><b>Exceptions.</b> {@link #execute} declares the checked exception of the call, and throws the
 * call's own failure once retries give up, or the policies' unchecked rejections
 * ({@link CallNotPermittedException}, {@link RateLimitExceededException}, {@link LimitExceededException},
 * {@link CallTimeoutException}, {@link BulkheadFullException}), or a {@link ResultRejectedException} for a result that the retry's
 * classifier rejected, with {@link Builder#onExhaustedResult onExhaustedResult(THROW)}. A fallback may return a result, rethrow the failure it was given, or
 * throw an unchecked exception; any other checked exception it throws is wrapped in an
 * {@link java.lang.reflect.UndeclaredThrowableException}.
 *
 * <p><b>Cost.</b> {@link #execute} runs the same allocation-free protocol as generated code, and an
 * unused policy costs one null check of a final field. Unlike generated code, one pipeline class serves
 * every call site, so the JIT sees many different calls at the same place; prefer annotations where
 * they can be used.
 *
 * @param <T> the result type of the protected calls
 */
public final class Pipeline<T> {

    private static final @Nullable Object[] NO_ARGUMENTS = {};
    private static final String CONFIG = "config";
    private static final String OVERRIDES = "overrides";
    private static final String CLASSIFIER = "classifier";
    private static final String PIPELINE_PREFIX = "Pipeline '";

    private final String id;
    private final @Nullable FallbackPolicy fallback;
    private final @Nullable FallbackHandler<? extends T> fallbackHandler;
    private final @Nullable FallbackHandler<? extends CompletionStage<T>> asyncFallbackHandler;
    private final @Nullable Recovery<T> recovery;
    private final @Nullable RetryPolicy retry;
    private final @Nullable HedgePolicy hedge;
    private final @Nullable Classifier<? super T> retryClassifier;
    private final boolean throwOnExhaustedResult;
    private final @Nullable CircuitBreakerPolicy circuitBreaker;
    private final @Nullable Classifier<? super T> circuitBreakerClassifier;
    private final @Nullable RateLimiterPolicy rateLimiter;
    private final @Nullable AdaptiveLimitPolicy adaptiveLimit;
    private final @Nullable TimeoutPolicy timeout;
    private final @Nullable BulkheadPolicy bulkhead;

    private Pipeline(Builder<T> builder) {
        ResilienceRegistry registry = builder.registry;
        this.id = builder.id;
        Step<RetryConfig.Builder> retryStep = builder.retry;
        this.retry = retryStep == null ? null
                : registry.retryPolicy(id, "", retryStep.config(), retryStep.overrides(), builder.locked);
        Step<HedgeConfig.Builder> hedgeStep = builder.hedge;
        this.hedge = hedgeStep == null ? null
                : registry.hedgePolicy(id, "", hedgeStep.config(), hedgeStep.overrides(), builder.locked);
        Step<CircuitBreakerConfig.Builder> circuitBreakerStep = builder.circuitBreaker;
        this.circuitBreaker = circuitBreakerStep == null ? null
                : registry.circuitBreakerPolicy(id, "", circuitBreakerStep.config(), circuitBreakerStep.overrides(), builder.locked);
        Step<RateLimiterConfig.Builder> rateLimiterStep = builder.rateLimiter;
        this.rateLimiter = rateLimiterStep == null ? null
                : registry.rateLimiterPolicy(id, "", rateLimiterStep.config(), rateLimiterStep.overrides(), builder.locked);
        Step<AdaptiveLimitConfig.Builder> adaptiveLimitStep = builder.adaptiveLimit;
        this.adaptiveLimit = adaptiveLimitStep == null ? null
                : registry.adaptiveLimitPolicy(id, "", adaptiveLimitStep.config(), adaptiveLimitStep.overrides(), builder.locked);
        Step<TimeoutConfig.Builder> timeoutStep = builder.timeout;
        this.timeout = timeoutStep == null ? null
                : registry.timeoutPolicy(id, "", timeoutStep.config(), timeoutStep.overrides(), builder.locked);
        Step<BulkheadConfig.Builder> bulkheadStep = builder.bulkhead;
        this.bulkhead = bulkheadStep == null ? null
                : registry.bulkheadPolicy(id, "", bulkheadStep.config(), bulkheadStep.overrides(), builder.locked);
        this.retryClassifier = retryStep == null ? null : ownOrShared(builder.retryClassifier, builder.classifier);
        this.circuitBreakerClassifier = circuitBreakerStep == null ? null
                : ownOrShared(builder.circuitBreakerClassifier, builder.classifier);
        this.throwOnExhaustedResult = builder.onExhaustedResult == ExhaustedResult.THROW;
        FallbackHandler<? extends T> handler = builder.fallbackHandler;
        FallbackHandler<? extends CompletionStage<T>> asyncHandler = builder.asyncFallbackHandler;
        this.fallbackHandler = handler;
        this.asyncFallbackHandler = asyncHandler;
        if (handler == null && asyncHandler == null) {
            this.fallback = null;
            this.recovery = null;
        } else {
            FallbackPolicy policy = registry.fallbackPolicy(id, builder.fallbackApplyOn, builder.fallbackSkipOn);
            this.fallback = policy;
            if (asyncHandler != null) {
                // FallbackPolicy turns a null stage into a NullPointerException.
                this.recovery = (failure, attempts) -> asyncHandler.apply(policy.context(failure, attempts, NO_ARGUMENTS));
            } else {
                FallbackHandler<? extends T> sync = Objects.requireNonNull(handler);
                this.recovery = (failure, attempts) ->
                        CompletableFuture.<T>completedFuture(sync.apply(policy.context(failure, attempts, NO_ARGUMENTS)));
            }
        }
    }

    /** The policy's own classifier if it has one, else the pipeline's shared one. */
    private static <T> @Nullable Classifier<? super T> ownOrShared(@Nullable Classifier<? super T> own,
            @Nullable Classifier<? super T> shared) {
        return own != null ? own : shared;
    }

    /** A synchronous call with only an asynchronous fallback waits (not interruptibly) for the fallback's stage. */
    // java:S2583: user code can return null despite the contract; this check turns it into a clear failure
    @SuppressWarnings("java:S2583")
    private static <T> T await(FallbackHandler<? extends CompletionStage<T>> handler, FallbackContext context)
            throws Throwable {
        @Nullable CompletionStage<T> stage = handler.apply(context); // user code may return null
        if (stage == null) {
            throw new NullPointerException("The asynchronous fallback of pipeline '" + context.instanceId()
                    + "' returned null instead of a CompletionStage");
        }
        try {
            return stage.toCompletableFuture().join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            throw cause != null ? cause : e;
        }
    }

    /**
     * Starts a pipeline.
     *
     * @param registry where the policies' configurations and instances come from
     * @param id the id of the pipeline's policy instances, for example {@code "payments"}
     * @param <T> the result type of the protected calls
     * @return a builder with no policies
     */
    public static <T> Builder<T> builder(ResilienceRegistry registry, String id) {
        return new Builder<>(registry, id);
    }

    /**
     * Starts a pipeline, with the result type given as a class so that it need not be written as a type
     * argument.
     *
     * @param registry where the policies' configurations and instances come from
     * @param id the id of the pipeline's policy instances, for example {@code "payments"}
     * @param type the result type of the protected calls
     * @param <T> the result type of the protected calls
     * @return a builder with no policies
     */
    public static <T> Builder<T> builder(ResilienceRegistry registry, String id, Class<T> type) {
        Objects.requireNonNull(type, "type");
        return new Builder<>(registry, id);
    }

    /** Returns the id of the pipeline's policy instances. */
    public String id() {
        return id;
    }

    /**
     * Runs a call with no result through the pipeline, on the calling thread, with the same policies and
     * semantics as {@link #execute}. A classifier receives {@code null} as the result of a call that
     * returns normally, and the fallback's result, if one runs, is discarded. Allocates nothing on success,
     * like {@link #execute}.
     *
     * @param call the call to protect; called again by the retry, if there is one
     * @param <X> the checked exception the call may throw
     * @throws X the call's last failure, when no fallback handles it
     */
    public <X extends Throwable> void run(PipelineRunnable<X> call) throws X {
        execute(Pipeline.<T, X>asCall(call));
    }

    /** A runnable's {@code call()} always returns {@code null}, which every {@code T} accepts. */
    @SuppressWarnings("unchecked")
    private static <T, X extends Throwable> PipelineCall<? extends T, X> asCall(PipelineRunnable<X> call) {
        return (PipelineCall<? extends T, X>) (PipelineCall<?, X>) Objects.requireNonNull(call, "call");
    }

    /**
     * Runs a call through the pipeline, on the calling thread.
     *
     * @param call the call to protect; called again by the retry, if there is one
     * @param <X> the checked exception the call may throw
     * @return the call's result, or the fallback's
     * @throws X the call's last failure, when no fallback handles it
     * @throws IllegalStateException if the pipeline has a hedge, which only works with {@link #executeAsync}
     */
    // java:S1181: a resilience layer must see every failure the call throws, Errors included, and relay it unchanged
    @SuppressWarnings("java:S1181")
    public <X extends Throwable> T execute(PipelineCall<? extends T, X> call) throws X {
        if (hedge != null) {
            throw hedgeIsAsyncOnly();
        }
        RetryPolicy retryPolicy = this.retry;
        if (retryPolicy != null) {
            Classifier<? super T> classifier = retryClassifier;
            return classifier == null ? retrying(retryPolicy, call) : classifiedRetrying(retryPolicy, classifier, call);
        }
        FallbackPolicy fallbackPolicy = this.fallback;
        if (fallbackPolicy == null) {
            return circuitBreakerLayer(call);
        }
        try {
            return circuitBreakerLayer(call);
        } catch (Throwable e) {
            if (!fallbackPolicy.handles(e)) {
                throw e;
            }
            return fallback(e, 1);
        }
    }

    /** Cold path of {@link #execute}: a hedge needs an asynchronous call. */
    private IllegalStateException hedgeIsAsyncOnly() {
        return new IllegalStateException(ErrorCode.HEDGE_NOT_ASYNC.message(PIPELINE_PREFIX + id + "' has a hedge, which"
                + " only works with executeAsync: a hedge starts the call again while the first attempt is still running,"
                + " which needs a call that returns a CompletionStage. Call executeAsync(() -> client.callAsync(...)), or"
                + " remove .hedge(...) from the pipeline."));
    }

    // java:S1181: a resilience layer must see every failure the call throws, Errors included, and relay it unchanged
    @SuppressWarnings("java:S1181")
    private <X extends Throwable> T retrying(RetryPolicy retry, PipelineCall<? extends T, X> call) throws X {
        long start = retry.start();
        int attempts = 0;
        while (true) {
            try {
                return circuitBreakerLayer(call);
            } catch (ClassifierFailure inner) {
                return classifierFailed(retry, Objects.requireNonNull(inner.getCause()), attempts + 1);
            } catch (Throwable e) {
                long delay = retry.onFailure(++attempts, e, start);
                if (delay < 0 || !retry.pause(delay)) {
                    FallbackPolicy fallbackPolicy = this.fallback;
                    if (fallbackPolicy == null || !fallbackPolicy.handles(e)) {
                        throw e;
                    }
                    return fallback(e, attempts);
                }
            }
        }
    }

    /** Same as {@link #retrying}, with the classifier's verdict on exceptions and results, as in generated code. */
    // java:S1181: a resilience layer must see every failure, and a classifier's Error must fail the call too.
    // java:S3776: a per-call method, kept in one piece like the generated retry loop it mirrors (JIT inlining).
    @SuppressWarnings({"java:S1181", "java:S3776"})
    private <X extends Throwable> T classifiedRetrying(RetryPolicy retry, Classifier<? super T> classifier,
            PipelineCall<? extends T, X> call) throws X {
        long start = retry.start();
        int attempts = 0;
        while (true) {
            T result;
            try {
                result = circuitBreakerLayer(call);
            } catch (ClassifierFailure inner) {
                return classifierFailed(retry, Objects.requireNonNull(inner.getCause()), attempts + 1);
            } catch (Throwable e) {
                Classifier.Outcome verdict;
                try {
                    verdict = classifier.classify(null, e);
                } catch (RuntimeException | Error classifierFailure) {
                    return classifierFailed(retry, classifierFailure, attempts + 1);
                }
                long delay = retry.onFailure(++attempts, e, start, verdict);
                if (delay < 0 || !retry.pause(delay)) {
                    FallbackPolicy fallbackPolicy = this.fallback;
                    if (fallbackPolicy == null || !fallbackPolicy.handles(e)) {
                        throw e;
                    }
                    return fallback(e, attempts);
                }
                continue;
            }
            Classifier.Outcome verdict;
            try {
                verdict = classifier.classify(result, null);
            } catch (RuntimeException | Error classifierFailure) {
                return classifierFailed(retry, classifierFailure, attempts + 1);
            }
            if (verdict != Classifier.Outcome.FAILURE) {
                return result;
            }
            long wait = retry.onFailedResult(++attempts, start);
            if (wait < 0 || !retry.pause(wait)) {
                // Rejected, but no retries left: the last result, or a ResultRejectedException.
                return throwOnExhaustedResult ? giveUp(retry.rejected(result, attempts), attempts) : result;
            }
        }
    }

    /** The classifier (the retry's, or the breaker's) threw: the retries end with its exception, as in generated code. */
    private T classifierFailed(RetryPolicy retry, Throwable classifierFailure, int attempts) {
        retry.onClassifierFailure(attempts, classifierFailure);
        return giveUp(classifierFailure, attempts);
    }

    /**
     * Ends the retries with an unchecked failure (the classifier's, or a {@link ResultRejectedException}):
     * the fallback's result if it handles it.
     */
    private T giveUp(Throwable failure, int attempts) {
        FallbackPolicy fallbackPolicy = this.fallback;
        if (fallbackPolicy == null || !fallbackPolicy.handles(failure)) {
            throw Pipeline.<RuntimeException>sneaky(failure); // unchecked: thrown by a Classifier
        }
        return fallback(failure, attempts);
    }

    // java:S1181: a resilience layer must see every failure the call throws, Errors included, and relay it unchanged
    @SuppressWarnings("java:S1181")
    private T fallback(Throwable failure, int attempts) {
        FallbackHandler<? extends T> handler = fallbackHandler;
        FallbackPolicy policy = Objects.requireNonNull(fallback);
        try {
            FallbackContext context = policy.context(failure, attempts, NO_ARGUMENTS);
            return handler != null ? handler.apply(context) : await(Objects.requireNonNull(asyncFallbackHandler), context);
        } catch (Throwable thrown) {
            if (thrown == failure) {
                // The call's own failure: an unchecked exception, or the X that execute declares.
                throw Pipeline.<RuntimeException>sneaky(thrown);
            }
            throw FallbackPolicy.unchecked(thrown);
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> E sneaky(Throwable failure) throws E {
        throw (E) failure;
    }

    // java:S1181: a resilience layer must see every failure the call throws, Errors included, and relay it unchanged
    @SuppressWarnings("java:S1181")
    private <X extends Throwable> T circuitBreakerLayer(PipelineCall<? extends T, X> call) throws X {
        CircuitBreakerPolicy cb = this.circuitBreaker;
        if (cb == null) {
            return rateLimiterLayer(call);
        }
        Classifier<? super T> classifier = circuitBreakerClassifier;
        if (classifier != null) {
            return classifiedCircuitBreakerLayer(cb, classifier, call);
        }
        long token = cb.acquire();
        if (token < 0) {
            throw cb.rejection();
        }
        long started = cb.startTimer();
        try {
            T result = rateLimiterLayer(call);
            cb.onSuccess(token, started);
            return result;
        } catch (Throwable e) {
            cb.onFailure(token, started, e);
            throw e;
        }
    }

    /**
     * The breaker layer with a classifier, as in generated code: a throwing classifier records a failure,
     * and its exception goes to the retry wrapped in a {@link ClassifierFailure}, so that the retry ends
     * the call with it instead of classifying it again.
     */
    // java:S1181: a resilience layer must see every failure, and a classifier's Error must fail the call too
    @SuppressWarnings("java:S1181")
    private <X extends Throwable> T classifiedCircuitBreakerLayer(CircuitBreakerPolicy cb,
            Classifier<? super T> classifier, PipelineCall<? extends T, X> call) throws X {
        long token = cb.acquire();
        if (token < 0) {
            throw cb.rejection();
        }
        long started = cb.startTimer();
        T result;
        try {
            result = rateLimiterLayer(call);
        } catch (Throwable e) {
            Classifier.Outcome verdict;
            try {
                verdict = classifier.classify(null, e);
            } catch (RuntimeException | Error classifierFailure) {
                cb.onResult(token, started, Classifier.Outcome.FAILURE);
                throw classifierFailure(classifierFailure);
            }
            cb.onFailure(token, started, e, verdict);
            throw e;
        }
        Classifier.Outcome verdict;
        try {
            verdict = classifier.classify(result, null);
        } catch (RuntimeException | Error classifierFailure) {
            cb.onResult(token, started, Classifier.Outcome.FAILURE);
            throw classifierFailure(classifierFailure);
        }
        cb.onResult(token, started, verdict);
        return result;
    }

    /** The breaker classifier's exception as the breaker layer throws it: wrapped for the retry, if there is one. */
    private RuntimeException classifierFailure(Throwable classifierFailure) {
        if (retry != null) {
            return new ClassifierFailure(classifierFailure);
        }
        throw Pipeline.<RuntimeException>sneaky(classifierFailure); // unchecked: thrown by a Classifier
    }

    private <X extends Throwable> T rateLimiterLayer(PipelineCall<? extends T, X> call) throws X {
        RateLimiterPolicy limiter = this.rateLimiter;
        if (limiter != null && !limiter.tryAcquire()) {
            throw limiter.rejection();
        }
        return adaptiveLimitLayer(call);
    }

    // java:S1181: a resilience layer must see every failure the call throws, Errors included, and relay it unchanged
    @SuppressWarnings("java:S1181")
    private <X extends Throwable> T adaptiveLimitLayer(PipelineCall<? extends T, X> call) throws X {
        AdaptiveLimitPolicy limit = this.adaptiveLimit;
        if (limit == null) {
            return timeoutLayer(call);
        }
        long start = limit.acquire();
        T result;
        try {
            result = timeoutLayer(call);
        } catch (Throwable e) {
            limit.onFailure(start, e);
            throw e;
        }
        limit.onSuccess(start);
        return result;
    }

    // java:S1181: a resilience layer must see every failure the call throws, Errors included, and relay it unchanged
    @SuppressWarnings("java:S1181")
    private <X extends Throwable> T timeoutLayer(PipelineCall<? extends T, X> call) throws X {
        TimeoutPolicy timeoutPolicy = this.timeout;
        if (timeoutPolicy == null) {
            return bulkheadLayer(call);
        }
        TimeoutPolicy.Scope scope = timeoutPolicy.enter();
        T result;
        try {
            result = bulkheadLayer(call);
        } catch (Throwable e) {
            timeoutPolicy.failed(scope, e);
            throw e;
        }
        timeoutPolicy.succeeded(scope);
        return result;
    }

    private <X extends Throwable> T bulkheadLayer(PipelineCall<? extends T, X> call) throws X {
        BulkheadPolicy bulkheadPolicy = this.bulkhead;
        if (bulkheadPolicy == null) {
            return call.call();
        }
        if (!bulkheadPolicy.tryEnter()) {
            throw bulkheadPolicy.rejection();
        }
        try {
            return call.call();
        } finally {
            bulkheadPolicy.exit();
        }
    }

    /**
     * Runs an asynchronous call through the pipeline, using each policy's asynchronous form: no thread
     * blocks while waiting for a retry delay, a permit or a slot, and a timeout completes the result with
     * {@link CallTimeoutException} without waiting for the call. A call that throws instead of returning
     * a stage, or that returns {@code null}, counts as a failed call. Cancelling the returned future
     * cancels the call inside it through every policy (no further attempt, no fallback, nothing recorded
     * by the breaker, the bulkhead slot freed), as for generated async methods.
     *
     * @param call starts the call to protect and returns its stage; called again by the retry, if there is one
     * @return a future that completes with the call's result, the fallback's, or the last failure
     *     (never wrapped in a {@code CompletionException})
     */
    public CompletableFuture<T> executeAsync(PipelineCall<? extends CompletionStage<T>, ?> call) {
        Objects.requireNonNull(call, "call");
        AsyncCall<T> next = call::call;
        Layer<T> outer = null; // the outermost policy so far, returned as is so that cancelling it reaches the call
        BulkheadPolicy bulkheadPolicy = this.bulkhead;
        if (bulkheadPolicy != null) {
            AsyncCall<T> inner = next;
            outer = () -> bulkheadPolicy.executeAsync(inner);
            next = outer;
        }
        TimeoutPolicy timeoutPolicy = this.timeout;
        if (timeoutPolicy != null) {
            AsyncCall<T> inner = next;
            outer = () -> timeoutPolicy.executeAsync(inner);
            next = outer;
        }
        AdaptiveLimitPolicy limitPolicy = this.adaptiveLimit;
        if (limitPolicy != null) {
            AsyncCall<T> inner = next;
            outer = () -> limitPolicy.executeAsync(inner);
            next = outer;
        }
        RateLimiterPolicy limiter = this.rateLimiter;
        if (limiter != null) {
            AsyncCall<T> inner = next;
            outer = () -> limiter.executeAsync(inner);
            next = outer;
        }
        CircuitBreakerPolicy breaker = this.circuitBreaker;
        if (breaker != null) {
            AsyncCall<T> inner = next;
            Classifier<? super T> classifier = circuitBreakerClassifier;
            boolean insideRetry = this.retry != null;
            outer = classifier == null ? () -> breaker.executeAsync(inner)
                    : () -> breaker.executeAsync(inner, classifier, insideRetry);
            next = outer;
        }
        RetryPolicy retryPolicy = this.retry;
        if (retryPolicy != null) {
            Classifier<? super T> classifier = retryClassifier;
            return classifier == null ? retryPolicy.executeAsync(next, fallback, recovery)
                    : retryPolicy.executeAsync(next, classifier, throwOnExhaustedResult, fallback, recovery);
        }
        HedgePolicy hedgePolicy = this.hedge;
        if (hedgePolicy != null) {
            return hedgePolicy.executeAsync(next, fallback, recovery);
        }
        FallbackPolicy fallbackPolicy = this.fallback;
        Recovery<T> recoveryCall = this.recovery;
        if (fallbackPolicy != null && recoveryCall != null) {
            return fallbackPolicy.executeAsync(next, recoveryCall);
        }
        return outer != null ? outer.call() : start(next);
    }

    /**
     * Runs one call of a stream through the pipeline, for the reactor module's {@code Flux} operator; use
     * that operator rather than this method. Each attempt is one subscription to the source
     * ({@link StreamAttempt#subscribe()}). The retry and the fallback act only on a failure before the
     * stream's first element ({@link StreamCall#attempt}); the timeout restarts on every element; the
     * circuit breaker and the adaptive limit time the stream until its first element and hold it until it
     * ends; the bulkhead holds its slot until it ends; the rate limiter takes one permit per attempt. The
     * fallback's value becomes a stream of that one element.
     *
     * @param call the stream's call
     * @return a future that completes when the stream ends (with its failure, if it fails before its first
     *     element and nothing recovers it)
     * @throws IllegalStateException if the pipeline has a hedge or a classifier, which streams do not support
     */
    public CompletableFuture<Void> executeStreamAsync(StreamCall<T> call) {
        Objects.requireNonNull(call, "call");
        if (hedge != null || retryClassifier != null || circuitBreakerClassifier != null) {
            throw new IllegalStateException(ErrorCode.NOT_FOR_STREAMS.message(PIPELINE_PREFIX + id + "' has "
                    + (hedge != null ? "a hedge" : "a classifier") + ", which a Flux cannot use: a hedge would merge two"
                    + " streams, and a classifier judges one result while a stream has many. Remove "
                    + (hedge != null ? ".hedge(...)" : "the classifier") + ", or use a separate pipeline for the Flux."));
        }
        AttemptLayers next = StreamAttempt::subscribe;
        BulkheadPolicy bulkheadPolicy = this.bulkhead;
        if (bulkheadPolicy != null) {
            AttemptLayers inner = next;
            next = attempt -> bulkheadPolicy.executeAsync(() -> inner.start(attempt));
        }
        TimeoutPolicy timeoutPolicy = this.timeout;
        if (timeoutPolicy != null) {
            AttemptLayers inner = next;
            next = attempt -> timeoutPolicy.executeStreamAsync(() -> inner.start(attempt), attempt);
        }
        AdaptiveLimitPolicy limitPolicy = this.adaptiveLimit;
        if (limitPolicy != null) {
            AttemptLayers inner = next;
            next = attempt -> limitPolicy.executeStreamAsync(() -> inner.start(attempt), attempt);
        }
        RateLimiterPolicy limiter = this.rateLimiter;
        if (limiter != null) {
            AttemptLayers inner = next;
            next = attempt -> limiter.executeAsync(() -> inner.start(attempt));
        }
        CircuitBreakerPolicy breaker = this.circuitBreaker;
        if (breaker != null) {
            AttemptLayers inner = next;
            next = attempt -> breaker.executeStreamAsync(() -> inner.start(attempt), attempt);
        }
        AttemptLayers layers = next;
        AsyncCall<Void> attempt = () -> call.attempt(layers);
        Recovery<T> recoveryCall = this.recovery;
        Recovery<Void> streamRecovery = recoveryCall == null ? null
                : (failure, attempts) -> recoveryCall.recover(failure, attempts).thenCompose(call::recover);
        RetryPolicy retryPolicy = this.retry;
        if (retryPolicy != null) {
            return retryPolicy.executeAsync(attempt, fallback, streamRecovery);
        }
        FallbackPolicy fallbackPolicy = this.fallback;
        if (fallbackPolicy != null && streamRecovery != null) {
            return fallbackPolicy.executeAsync(attempt, streamRecovery);
        }
        return start(attempt);
    }

    /**
     * One policy layer of {@link #executeAsync}: an {@code AsyncCall} for the layer around it, and the
     * policy's own future when it is the outermost one, which must reach the caller unwrapped so that
     * cancelling it cancels the layers inside.
     */
    @FunctionalInterface
    private interface Layer<T> extends AsyncCall<T> {
        @Override
        CompletableFuture<T> call();
    }

    /** Starts the outermost layer when no retry or fallback does it. */
    // java:S2583: user code can return null despite the contract; this check turns it into a clear failure
    @SuppressWarnings({"unchecked", "java:S2583"})
    private static <T> CompletableFuture<T> start(AsyncCall<T> call) {
        @Nullable CompletionStage<T> stage; // user code may return null: checked below
        try {
            stage = call.call();
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
        if (stage == null) {
            return CompletableFuture.failedFuture(new NullPointerException("An asynchronous call returned null"
                    + " instead of a CompletionStage"));
        }
        if (stage.getClass() == CompletableFuture.class) {
            // Exactly a CompletableFuture: not a subclass such as minimalCompletionStage(), whose join() throws.
            return (CompletableFuture<T>) stage;
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        stage.whenComplete((value, failure) -> {
            if (failure == null) {
                result.complete(value);
            } else {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder("Pipeline[").append(id);
        if (fallback != null) {
            text.append(", fallback");
        }
        if (retry != null) {
            text.append(", retry");
        }
        if (hedge != null) {
            text.append(", hedge");
        }
        if (circuitBreaker != null) {
            text.append(", circuitBreaker");
        }
        if (rateLimiter != null) {
            text.append(", rateLimiter");
        }
        if (adaptiveLimit != null) {
            text.append(", adaptiveLimit");
        }
        if (timeout != null) {
            text.append(", timeout");
        }
        if (bulkhead != null) {
            text.append(", bulkhead");
        }
        return text.append(']').toString();
    }

    /**
     * What a retry with a classifier does when it gives up on a result that the classifier rejected, like
     * {@code @Retry(onExhaustedResult = ...)}.
     */
    public enum ExhaustedResult {
        /** Return the last result (the default). The fallback does not run. */
        RETURN,
        /**
         * Throw a {@link ResultRejectedException} holding the last result, which goes to the fallback like
         * any other failure.
         */
        THROW
    }

    /** A configured policy: a named configuration, and overrides applied on top of it. */
    private record Step<B>(String config, @Nullable Consumer<B> overrides) {
    }

    /**
     * Builder for {@link Pipeline}. Each policy is added at most once, from the registry's defaults, a
     * named configuration, overrides, or a named configuration with overrides. Configurations are
     * resolved, and policy instances obtained from the registry, by {@link #build()}.
     *
     * @param <T> the result type of the protected calls
     */
    public static final class Builder<T> {
        private final ResilienceRegistry registry;
        private final String id;
        private @Nullable Step<RetryConfig.Builder> retry;
        private @Nullable Step<HedgeConfig.Builder> hedge;
        private @Nullable Step<CircuitBreakerConfig.Builder> circuitBreaker;
        private @Nullable Step<RateLimiterConfig.Builder> rateLimiter;
        private @Nullable Step<AdaptiveLimitConfig.Builder> adaptiveLimit;
        private @Nullable Step<TimeoutConfig.Builder> timeout;
        private @Nullable Step<BulkheadConfig.Builder> bulkhead;
        private @Nullable Classifier<? super T> classifier;
        private @Nullable Classifier<? super T> retryClassifier;
        private @Nullable Classifier<? super T> circuitBreakerClassifier;
        private @Nullable FallbackHandler<? extends T> fallbackHandler;
        private @Nullable FallbackHandler<? extends CompletionStage<T>> asyncFallbackHandler;
        private @Nullable ExhaustedResult onExhaustedResult;
        private List<Class<? extends Throwable>> fallbackApplyOn = List.of(Throwable.class);
        private List<Class<? extends Throwable>> fallbackSkipOn = List.of();
        private boolean locked;

        private Builder(ResilienceRegistry registry, String id) {
            this.registry = Objects.requireNonNull(registry, "registry");
            this.id = Objects.requireNonNull(id, "id");
            if (id.isBlank()) {
                throw new IllegalArgumentException(ErrorCode.BLANK_PIPELINE_ID.message(
                        "The pipeline id must not be blank. Pass the id that names the pipeline's policy instances,"
                        + " for example \"payments\"."));
            }
        }

        /**
         * Adds a retry with the registry's retry defaults.
         *
         * @return this builder
         */
        public Builder<T> retry() {
            return retry("", null);
        }

        /**
         * Adds a retry with a named configuration.
         *
         * @param config the name of a configuration registered with {@code ResilienceRegistry.Builder.retry}
         * @return this builder
         */
        public Builder<T> retry(String config) {
            return retry(config, null);
        }

        /**
         * Adds a retry with the registry's retry defaults and overrides.
         *
         * @param overrides applied to a builder that starts from the defaults
         * @return this builder
         */
        public Builder<T> retry(Consumer<RetryConfig.Builder> overrides) {
            return retry("", Objects.requireNonNull(overrides, OVERRIDES));
        }

        /**
         * Adds a retry with a named configuration and overrides.
         *
         * @param config the name of a configuration, or {@code ""} for the defaults
         * @param overrides applied to a builder that starts from that configuration; {@code null} for none
         * @return this builder
         */
        public Builder<T> retry(String config, @Nullable Consumer<RetryConfig.Builder> overrides) {
            once(retry, "retry");
            retry = new Step<>(Objects.requireNonNull(config, CONFIG), overrides);
            return this;
        }

        /**
         * Adds a hedge with the registry's hedge defaults: the call is started again when it has produced no
         * result for {@code delay}, or at once when it fails, and the first success wins. Only for idempotent
         * calls, and only with {@link Pipeline#executeAsync}; cannot be combined with a retry.
         *
         * @return this builder
         */
        public Builder<T> hedge() {
            return hedge("", null);
        }

        /**
         * Adds a hedge with a named configuration. See {@link #hedge()}.
         *
         * @param config the name of a configuration registered with {@code ResilienceRegistry.Builder.hedge}
         * @return this builder
         */
        public Builder<T> hedge(String config) {
            return hedge(config, null);
        }

        /**
         * Adds a hedge with the registry's hedge defaults and overrides. See {@link #hedge()}.
         *
         * @param overrides applied to a builder that starts from the defaults
         * @return this builder
         */
        public Builder<T> hedge(Consumer<HedgeConfig.Builder> overrides) {
            return hedge("", Objects.requireNonNull(overrides, OVERRIDES));
        }

        /**
         * Adds a hedge with a named configuration and overrides. See {@link #hedge()}.
         *
         * @param config the name of a configuration, or {@code ""} for the defaults
         * @param overrides applied to a builder that starts from that configuration; {@code null} for none
         * @return this builder
         */
        public Builder<T> hedge(String config, @Nullable Consumer<HedgeConfig.Builder> overrides) {
            once(hedge, "hedge");
            hedge = new Step<>(Objects.requireNonNull(config, CONFIG), overrides);
            return this;
        }

        /**
         * Adds a circuit breaker with the registry's defaults.
         *
         * @return this builder
         */
        public Builder<T> circuitBreaker() {
            return circuitBreaker("", null);
        }

        /**
         * Adds a circuit breaker with a named configuration.
         *
         * @param config the name of a configuration registered with
         *     {@code ResilienceRegistry.Builder.circuitBreaker}
         * @return this builder
         */
        public Builder<T> circuitBreaker(String config) {
            return circuitBreaker(config, null);
        }

        /**
         * Adds a circuit breaker with the registry's defaults and overrides.
         *
         * @param overrides applied to a builder that starts from the defaults
         * @return this builder
         */
        public Builder<T> circuitBreaker(Consumer<CircuitBreakerConfig.Builder> overrides) {
            return circuitBreaker("", Objects.requireNonNull(overrides, OVERRIDES));
        }

        /**
         * Adds a circuit breaker with a named configuration and overrides.
         *
         * @param config the name of a configuration, or {@code ""} for the defaults
         * @param overrides applied to a builder that starts from that configuration; {@code null} for none
         * @return this builder
         */
        public Builder<T> circuitBreaker(String config, @Nullable Consumer<CircuitBreakerConfig.Builder> overrides) {
            once(circuitBreaker, "circuitBreaker");
            circuitBreaker = new Step<>(Objects.requireNonNull(config, CONFIG), overrides);
            return this;
        }

        /**
         * Adds a rate limiter with the registry's defaults.
         *
         * @return this builder
         */
        public Builder<T> rateLimiter() {
            return rateLimiter("", null);
        }

        /**
         * Adds a rate limiter with a named configuration.
         *
         * @param config the name of a configuration registered with
         *     {@code ResilienceRegistry.Builder.rateLimiter}
         * @return this builder
         */
        public Builder<T> rateLimiter(String config) {
            return rateLimiter(config, null);
        }

        /**
         * Adds a rate limiter with the registry's defaults and overrides.
         *
         * @param overrides applied to a builder that starts from the defaults
         * @return this builder
         */
        public Builder<T> rateLimiter(Consumer<RateLimiterConfig.Builder> overrides) {
            return rateLimiter("", Objects.requireNonNull(overrides, OVERRIDES));
        }

        /**
         * Adds a rate limiter with a named configuration and overrides.
         *
         * @param config the name of a configuration, or {@code ""} for the defaults
         * @param overrides applied to a builder that starts from that configuration; {@code null} for none
         * @return this builder
         */
        public Builder<T> rateLimiter(String config, @Nullable Consumer<RateLimiterConfig.Builder> overrides) {
            once(rateLimiter, "rateLimiter");
            rateLimiter = new Step<>(Objects.requireNonNull(config, CONFIG), overrides);
            return this;
        }

        /**
         * Adds an adaptive limit with the registry's defaults.
         *
         * @return this builder
         */
        public Builder<T> adaptiveLimit() {
            return adaptiveLimit("", null);
        }

        /**
         * Adds an adaptive limit with a named configuration.
         *
         * @param config the name of a configuration registered with {@code ResilienceRegistry.Builder.adaptiveLimit}
         * @return this builder
         */
        public Builder<T> adaptiveLimit(String config) {
            return adaptiveLimit(config, null);
        }

        /**
         * Adds an adaptive limit with the registry's defaults and overrides.
         *
         * @param overrides applied to a builder that starts from the defaults
         * @return this builder
         */
        public Builder<T> adaptiveLimit(Consumer<AdaptiveLimitConfig.Builder> overrides) {
            return adaptiveLimit("", Objects.requireNonNull(overrides, OVERRIDES));
        }

        /**
         * Adds an adaptive limit with a named configuration and overrides.
         *
         * @param config the name of a configuration, or {@code ""} for the defaults
         * @param overrides applied to a builder that starts from that configuration; {@code null} for none
         * @return this builder
         */
        public Builder<T> adaptiveLimit(String config, @Nullable Consumer<AdaptiveLimitConfig.Builder> overrides) {
            once(adaptiveLimit, "adaptiveLimit");
            adaptiveLimit = new Step<>(Objects.requireNonNull(config, CONFIG), overrides);
            return this;
        }

        /**
         * Adds a timeout with the registry's defaults.
         *
         * @return this builder
         */
        public Builder<T> timeout() {
            return timeout("", null);
        }

        /**
         * Adds a timeout with a named configuration.
         *
         * @param config the name of a configuration registered with {@code ResilienceRegistry.Builder.timeout}
         * @return this builder
         */
        public Builder<T> timeout(String config) {
            return timeout(config, null);
        }

        /**
         * Adds a timeout with the registry's defaults and overrides.
         *
         * @param overrides applied to a builder that starts from the defaults
         * @return this builder
         */
        public Builder<T> timeout(Consumer<TimeoutConfig.Builder> overrides) {
            return timeout("", Objects.requireNonNull(overrides, OVERRIDES));
        }

        /**
         * Adds a timeout with a named configuration and overrides.
         *
         * @param config the name of a configuration, or {@code ""} for the defaults
         * @param overrides applied to a builder that starts from that configuration; {@code null} for none
         * @return this builder
         */
        public Builder<T> timeout(String config, @Nullable Consumer<TimeoutConfig.Builder> overrides) {
            once(timeout, "timeout");
            timeout = new Step<>(Objects.requireNonNull(config, CONFIG), overrides);
            return this;
        }

        /**
         * Adds a bulkhead with the registry's defaults.
         *
         * @return this builder
         */
        public Builder<T> bulkhead() {
            return bulkhead("", null);
        }

        /**
         * Adds a bulkhead with a named configuration.
         *
         * @param config the name of a configuration registered with {@code ResilienceRegistry.Builder.bulkhead}
         * @return this builder
         */
        public Builder<T> bulkhead(String config) {
            return bulkhead(config, null);
        }

        /**
         * Adds a bulkhead with the registry's defaults and overrides.
         *
         * @param overrides applied to a builder that starts from the defaults
         * @return this builder
         */
        public Builder<T> bulkhead(Consumer<BulkheadConfig.Builder> overrides) {
            return bulkhead("", Objects.requireNonNull(overrides, OVERRIDES));
        }

        /**
         * Adds a bulkhead with a named configuration and overrides.
         *
         * @param config the name of a configuration, or {@code ""} for the defaults
         * @param overrides applied to a builder that starts from that configuration; {@code null} for none
         * @return this builder
         */
        public Builder<T> bulkhead(String config, @Nullable Consumer<BulkheadConfig.Builder> overrides) {
            once(bulkhead, "bulkhead");
            bulkhead = new Step<>(Objects.requireNonNull(config, CONFIG), overrides);
            return this;
        }

        /**
         * Classifies results and exceptions for both the retry and the circuit breaker, like
         * {@code classifier = ...} on {@code @Retry} and {@code @CircuitBreaker}. See
         * {@link #retryClassifier} and {@link #circuitBreakerClassifier}, which win over this one for their
         * policy. At least one of the two policies must be in the pipeline.
         *
         * @param classifier decides whether each call is a success, a failure or ignored
         * @return this builder
         */
        public Builder<T> classifier(Classifier<? super T> classifier) {
            if (this.classifier != null) {
                throw setTwice("The classifier");
            }
            this.classifier = Objects.requireNonNull(classifier, CLASSIFIER);
            return this;
        }

        /**
         * Lets a classifier decide what the retry retries, like {@code @Retry(classifier = ...)}: only
         * {@code FAILURE} is retried; its verdict replaces {@code retryOn}, while {@code abortOn} still wins, and
         * a {@link CallNotPermittedException} is never retried.
         * A result classified {@code FAILURE} is retried too, and returned if the retries give up on it (or
         * thrown, see {@link #onExhaustedResult}). A classifier that throws ends the retries with its
         * exception.
         *
         * @param classifier decides whether each attempt is a success, a failure or ignored
         * @return this builder
         */
        public Builder<T> retryClassifier(Classifier<? super T> classifier) {
            if (retryClassifier != null) {
                throw setTwice("The retry classifier");
            }
            retryClassifier = Objects.requireNonNull(classifier, CLASSIFIER);
            return this;
        }

        /**
         * Chooses what the retry does when it gives up on a result that its classifier rejected, like
         * {@code @Retry(onExhaustedResult = ...)}: return it ({@link ExhaustedResult#RETURN}, the default) or
         * throw a {@link ResultRejectedException} that goes to the fallback ({@link ExhaustedResult#THROW}).
         * Needs a retry with a classifier ({@link #classifier} or {@link #retryClassifier}).
         *
         * @param onExhaustedResult what to do with the last rejected result
         * @return this builder
         */
        public Builder<T> onExhaustedResult(ExhaustedResult onExhaustedResult) {
            if (this.onExhaustedResult != null) {
                throw setTwice("onExhaustedResult");
            }
            this.onExhaustedResult = Objects.requireNonNull(onExhaustedResult, "onExhaustedResult");
            return this;
        }

        /**
         * Locks the values set on the steps ({@code retry(config, overrides)} and the others), like
         * {@code locked = true} on an annotation: they win over the per-instance overrides of this pipeline's
         * id ({@code instances.<id>.<policy>.*} or {@code retryInstance(id, ...)} and friends). Attributes the
         * steps do not set can still be overridden. Overrides that are ignored are logged and listed by
         * {@link ResilienceRegistry#lockedOverrides()}.
         *
         * @return this builder
         */
        public Builder<T> locked() {
            this.locked = true;
            return this;
        }

        /**
         * Lets a classifier decide what the circuit breaker records, like
         * {@code @CircuitBreaker(classifier = ...)}: its verdict replaces {@code recordOn}, while
         * {@code ignoreOn} still wins. A classifier that throws records a failure and fails the call.
         *
         * @param classifier decides whether each call is a success, a failure or ignored
         * @return this builder
         */
        public Builder<T> circuitBreakerClassifier(Classifier<? super T> classifier) {
            if (circuitBreakerClassifier != null) {
                throw setTwice("The circuit breaker classifier");
            }
            circuitBreakerClassifier = Objects.requireNonNull(classifier, CLASSIFIER);
            return this;
        }

        /**
         * Adds a fallback that computes an alternative result from the failure. It runs once retries give
         * up, for failures selected by {@link #fallbackApplyOn} and {@link #fallbackSkipOn} (by default,
         * every failure, including rejections by the other policies).
         *
         * <p>{@link Pipeline#executeAsync} uses it too, unless an asynchronous fallback is set
         * ({@link #fallbackAsync}).
         *
         * @param fallback returns the alternative result, or throws an unchecked exception to fail the call
         * @return this builder
         */
        public Builder<T> fallback(Function<? super Throwable, ? extends T> fallback) {
            Objects.requireNonNull(fallback, "fallback");
            return fallbackHandler(context -> fallback.apply(context.failure()));
        }

        /**
         * Adds a fallback that receives a {@link FallbackContext}: the failure, the number of attempts
         * made and the pipeline id ({@link FallbackContext#argumentCount()} is 0).
         *
         * @param handler returns the alternative result, or throws to fail the call
         * @return this builder
         */
        public Builder<T> fallbackHandler(FallbackHandler<? extends T> handler) {
            if (fallbackHandler != null) {
                throw setTwice("The fallback");
            }
            fallbackHandler = Objects.requireNonNull(handler, "handler");
            return this;
        }

        /**
         * Adds an asynchronous fallback, for {@link Pipeline#executeAsync}: it returns a stage instead of a
         * value, so that the alternative result can come from another asynchronous call. It runs for the same
         * failures as {@link #fallback}, once retries give up. A stage that fails fails the call with its
         * failure (unwrapped); a fallback that returns {@code null} fails it with a
         * {@link NullPointerException}.
         *
         * <p>Can be combined with {@link #fallback}: then {@link Pipeline#execute} uses the synchronous one
         * and {@link Pipeline#executeAsync} this one. Without a synchronous fallback, {@link Pipeline#execute}
         * and {@link Pipeline#run} wait for this one's stage.
         *
         * @param fallback returns a stage with the alternative result, or throws an unchecked exception to
         *     fail the call
         * @return this builder
         */
        public Builder<T> fallbackAsync(Function<? super Throwable, ? extends CompletionStage<T>> fallback) {
            Objects.requireNonNull(fallback, "fallback");
            return fallbackAsyncHandler(context -> fallback.apply(context.failure()));
        }

        /**
         * Same as {@link #fallbackAsync}, with a {@link FallbackContext}: the failure, the number of attempts
         * made and the pipeline id.
         *
         * @param handler returns a stage with the alternative result, or throws to fail the call
         * @return this builder
         */
        public Builder<T> fallbackAsyncHandler(FallbackHandler<? extends CompletionStage<T>> handler) {
            if (asyncFallbackHandler != null) {
                throw setTwice("The asynchronous fallback");
            }
            asyncFallbackHandler = Objects.requireNonNull(handler, "handler");
            return this;
        }

        /**
         * Selects the failures that get the fallback, like {@code @Fallback(applyOn = ...)}.
         *
         * @param applyOn failure types (and their subtypes); by default {@code Throwable}
         * @return this builder
         */
        public Builder<T> fallbackApplyOn(List<Class<? extends Throwable>> applyOn) {
            fallbackApplyOn = List.copyOf(applyOn);
            return this;
        }

        /**
         * Excludes failures from the fallback, like {@code @Fallback(skipOn = ...)}. Wins over
         * {@link #fallbackApplyOn}.
         *
         * @param skipOn failure types (and their subtypes) that never get the fallback; by default none
         * @return this builder
         */
        public Builder<T> fallbackSkipOn(List<Class<? extends Throwable>> skipOn) {
            fallbackSkipOn = List.copyOf(skipOn);
            return this;
        }

        private IllegalStateException setTwice(String what) {
            return new IllegalStateException(ErrorCode.SET_TWICE.message(what + " of pipeline '" + id
                    + "' is set twice. Set it once per builder."));
        }

        private void once(@Nullable Step<?> step, String kind) {
            if (step != null) {
                throw setTwice("The " + kind);
            }
        }

        /**
         * Resolves the configurations and obtains the policy instances from the registry. Can be called
         * again: pipelines built from the same builder share their instances.
         *
         * @return the pipeline
         * @throws IllegalArgumentException if a named configuration does not exist, or a configuration is invalid
         * @throws IllegalStateException if the id is already used by an instance with a different configuration,
         *     or the pipeline has both a retry and a hedge
         */
        public Pipeline<T> build() {
            if ((fallbackApplyOn.size() != 1 || fallbackApplyOn.get(0) != Throwable.class || !fallbackSkipOn.isEmpty())
                    && fallbackHandler == null && asyncFallbackHandler == null) {
                throw new IllegalStateException(ErrorCode.FALLBACK_FILTER_WITHOUT_FALLBACK.message(PIPELINE_PREFIX + id
                        + "' selects fallback failures but has no fallback. Add a fallback, or remove fallbackApplyOn and"
                        + " fallbackSkipOn."));
            }
            if (retry != null && hedge != null) {
                throw new IllegalStateException(ErrorCode.RETRY_WITH_HEDGE.message(PIPELINE_PREFIX + id
                        + "' has both a retry and a hedge. Keep one: a hedge already starts a new attempt when one fails"
                        + " (hedgeOn), and a retry around it would multiply the attempts."));
            }
            if (classifier != null && retry == null && circuitBreaker == null) {
                throw new IllegalStateException(ErrorCode.CLASSIFIER_WITHOUT_POLICY.message(PIPELINE_PREFIX + id
                        + "' has a classifier but no retry or circuit breaker. Add one of them, or remove the classifier."));
            }
            if (retryClassifier != null && retry == null) {
                throw new IllegalStateException(ErrorCode.RETRY_CLASSIFIER_WITHOUT_RETRY.message(PIPELINE_PREFIX + id
                        + "' has a retry classifier but no retry. Add a retry, or remove the retry classifier."));
            }
            if (onExhaustedResult != null && (retry == null || (retryClassifier == null && classifier == null))) {
                throw new IllegalStateException(ErrorCode.EXHAUSTED_RESULT_WITHOUT_CLASSIFIER.message(PIPELINE_PREFIX + id
                        + "' sets onExhaustedResult but has no retry with a classifier: only a classifier can reject a"
                        + " result. Add a retry and a classifier, or remove onExhaustedResult."));
            }
            if (circuitBreakerClassifier != null && circuitBreaker == null) {
                throw new IllegalStateException(ErrorCode.CIRCUIT_BREAKER_CLASSIFIER_WITHOUT_CIRCUIT_BREAKER.message(
                        PIPELINE_PREFIX + id + "' has a circuit breaker classifier but no circuit breaker. Add a circuit breaker, or"
                        + " remove the circuit breaker classifier."));
            }
            return new Pipeline<>(this);
        }
    }
}
