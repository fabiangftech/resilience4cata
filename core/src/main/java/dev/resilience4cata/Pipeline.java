package dev.resilience4cata;

import dev.resilience4cata.generated.AsyncCall;
import dev.resilience4cata.generated.BulkheadPolicy;
import dev.resilience4cata.generated.CircuitBreakerPolicy;
import dev.resilience4cata.generated.FallbackPolicy;
import dev.resilience4cata.generated.RateLimiterPolicy;
import dev.resilience4cata.generated.Recovery;
import dev.resilience4cata.generated.RetryPolicy;
import dev.resilience4cata.generated.TimeoutPolicy;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
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
 * }</pre>
 *
 * <p>Every policy is optional. They nest in the same order as in generated code, outermost first:
 * fallback, retry, circuit breaker, rate limiter, timeout, bulkhead, then the call. The order in which
 * the builder's methods are called does not matter. A {@link Classifier} can decide what the retry
 * retries and what the breaker records, results included ({@link Builder#classifier}).
 *
 * <p><b>Instances.</b> The pipeline's id plays the part of the {@code <interface>#<method>} id of
 * annotated methods: its policies are created by the registry under that id, so that
 * {@code registry.circuitBreaker("payments")} finds the breaker, and two pipelines (or a pipeline and an
 * annotation with {@code name = "payments"}) with the same id and configuration share their circuit
 * breaker, rate limiter, bulkhead and retry instances. The same id with a different configuration is an
 * error, as for annotations.
 *
 * <p><b>Exceptions.</b> {@link #execute} declares the checked exception of the call, and throws the
 * call's own failure once retries give up, or the policies' unchecked rejections
 * ({@link CallNotPermittedException}, {@link RateLimitExceededException}, {@link CallTimeoutException},
 * {@link BulkheadFullException}). A fallback may return a result, rethrow the failure it was given, or
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

    private final String id;
    private final @Nullable FallbackPolicy fallback;
    private final @Nullable FallbackHandler<? extends T> fallbackHandler;
    private final @Nullable Recovery<T> recovery;
    private final @Nullable RetryPolicy retry;
    private final @Nullable Classifier<? super T> retryClassifier;
    private final @Nullable CircuitBreakerPolicy circuitBreaker;
    private final @Nullable Classifier<? super T> circuitBreakerClassifier;
    private final @Nullable RateLimiterPolicy rateLimiter;
    private final @Nullable TimeoutPolicy timeout;
    private final @Nullable BulkheadPolicy bulkhead;

    private Pipeline(Builder<T> builder) {
        ResilienceRegistry registry = builder.registry;
        this.id = builder.id;
        Step<RetryConfig.Builder> retryStep = builder.retry;
        this.retry = retryStep == null ? null
                : registry.retryPolicy(id, "", retryStep.config(), retryStep.overrides());
        Step<CircuitBreakerConfig.Builder> circuitBreakerStep = builder.circuitBreaker;
        this.circuitBreaker = circuitBreakerStep == null ? null
                : registry.circuitBreakerPolicy(id, "", circuitBreakerStep.config(), circuitBreakerStep.overrides());
        Step<RateLimiterConfig.Builder> rateLimiterStep = builder.rateLimiter;
        this.rateLimiter = rateLimiterStep == null ? null
                : registry.rateLimiterPolicy(id, "", rateLimiterStep.config(), rateLimiterStep.overrides());
        Step<TimeoutConfig.Builder> timeoutStep = builder.timeout;
        this.timeout = timeoutStep == null ? null
                : registry.timeoutPolicy(id, "", timeoutStep.config(), timeoutStep.overrides());
        Step<BulkheadConfig.Builder> bulkheadStep = builder.bulkhead;
        this.bulkhead = bulkheadStep == null ? null
                : registry.bulkheadPolicy(id, "", bulkheadStep.config(), bulkheadStep.overrides());
        this.retryClassifier = retryStep == null ? null
                : builder.retryClassifier != null ? builder.retryClassifier : builder.classifier;
        this.circuitBreakerClassifier = circuitBreakerStep == null ? null
                : builder.circuitBreakerClassifier != null ? builder.circuitBreakerClassifier : builder.classifier;
        FallbackHandler<? extends T> handler = builder.fallbackHandler;
        if (handler == null) {
            this.fallback = null;
            this.fallbackHandler = null;
            this.recovery = null;
        } else {
            FallbackPolicy policy = registry.fallbackPolicy(id, builder.fallbackApplyOn, builder.fallbackSkipOn);
            this.fallback = policy;
            this.fallbackHandler = handler;
            this.recovery = (failure, attempts) ->
                    CompletableFuture.<T>completedFuture(handler.apply(policy.context(failure, attempts, NO_ARGUMENTS)));
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
     * Runs a call through the pipeline, on the calling thread.
     *
     * @param call the call to protect; called again by the retry, if there is one
     * @param <X> the checked exception the call may throw
     * @return the call's result, or the fallback's
     * @throws X the call's last failure, when no fallback handles it
     */
    public <X extends Throwable> T execute(PipelineCall<? extends T, X> call) throws X {
        RetryPolicy retry = this.retry;
        if (retry != null) {
            Classifier<? super T> classifier = retryClassifier;
            return classifier == null ? retrying(retry, call) : classifiedRetrying(retry, classifier, call);
        }
        FallbackPolicy fallback = this.fallback;
        if (fallback == null) {
            return circuitBreakerLayer(call);
        }
        try {
            return circuitBreakerLayer(call);
        } catch (Throwable e) {
            if (!fallback.handles(e)) {
                throw e;
            }
            return fallback(e, 1);
        }
    }

    private <X extends Throwable> T retrying(RetryPolicy retry, PipelineCall<? extends T, X> call) throws X {
        long start = retry.start();
        int attempts = 0;
        while (true) {
            try {
                return circuitBreakerLayer(call);
            } catch (Throwable e) {
                long delay = retry.onFailure(++attempts, e, start);
                if (delay < 0 || !retry.pause(delay)) {
                    FallbackPolicy fallback = this.fallback;
                    if (fallback == null || !fallback.handles(e)) {
                        throw e;
                    }
                    return fallback(e, attempts);
                }
            }
        }
    }

    /** Same as {@link #retrying}, with the classifier's verdict on exceptions and results, as in generated code. */
    private <X extends Throwable> T classifiedRetrying(RetryPolicy retry, Classifier<? super T> classifier,
            PipelineCall<? extends T, X> call) throws X {
        long start = retry.start();
        int attempts = 0;
        while (true) {
            T result;
            try {
                result = circuitBreakerLayer(call);
            } catch (Throwable e) {
                Classifier.Outcome verdict;
                try {
                    verdict = classifier.classify(null, e);
                } catch (RuntimeException | Error classifierFailure) {
                    return giveUp(classifierFailure, attempts + 1);
                }
                long delay = retry.onFailure(++attempts, e, start, verdict);
                if (delay < 0 || !retry.pause(delay)) {
                    FallbackPolicy fallback = this.fallback;
                    if (fallback == null || !fallback.handles(e)) {
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
                return giveUp(classifierFailure, attempts + 1);
            }
            if (verdict != Classifier.Outcome.FAILURE) {
                return result;
            }
            long wait = retry.onFailedResult(++attempts, start);
            if (wait < 0 || !retry.pause(wait)) {
                return result; // rejected, but no retries left: the last result
            }
        }
    }

    /** Ends the retries with an unchecked failure (the classifier's): the fallback's result if it handles it. */
    private T giveUp(Throwable failure, int attempts) {
        FallbackPolicy fallback = this.fallback;
        if (fallback == null || !fallback.handles(failure)) {
            throw Pipeline.<RuntimeException>sneaky(failure); // unchecked: thrown by a Classifier
        }
        return fallback(failure, attempts);
    }

    private T fallback(Throwable failure, int attempts) {
        FallbackHandler<? extends T> handler = Objects.requireNonNull(fallbackHandler);
        FallbackPolicy policy = Objects.requireNonNull(fallback);
        try {
            return handler.apply(policy.context(failure, attempts, NO_ARGUMENTS));
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

    /** The breaker layer with a classifier, as in generated code: a throwing classifier records a failure. */
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
                throw classifierFailure;
            }
            cb.onFailure(token, started, e, verdict);
            throw e;
        }
        Classifier.Outcome verdict;
        try {
            verdict = classifier.classify(result, null);
        } catch (RuntimeException | Error classifierFailure) {
            cb.onResult(token, started, Classifier.Outcome.FAILURE);
            throw classifierFailure;
        }
        cb.onResult(token, started, verdict);
        return result;
    }

    private <X extends Throwable> T rateLimiterLayer(PipelineCall<? extends T, X> call) throws X {
        RateLimiterPolicy rateLimiter = this.rateLimiter;
        if (rateLimiter != null && !rateLimiter.tryAcquire()) {
            throw rateLimiter.rejection();
        }
        return timeoutLayer(call);
    }

    private <X extends Throwable> T timeoutLayer(PipelineCall<? extends T, X> call) throws X {
        TimeoutPolicy timeout = this.timeout;
        if (timeout == null) {
            return bulkheadLayer(call);
        }
        TimeoutPolicy.Scope scope = timeout.enter();
        T result;
        try {
            result = bulkheadLayer(call);
        } catch (Throwable e) {
            timeout.failed(scope, e);
            throw e;
        }
        timeout.succeeded(scope);
        return result;
    }

    private <X extends Throwable> T bulkheadLayer(PipelineCall<? extends T, X> call) throws X {
        BulkheadPolicy bulkhead = this.bulkhead;
        if (bulkhead == null) {
            return call.call();
        }
        if (!bulkhead.tryEnter()) {
            throw bulkhead.rejection();
        }
        try {
            return call.call();
        } finally {
            bulkhead.exit();
        }
    }

    /**
     * Runs an asynchronous call through the pipeline, using each policy's asynchronous form: no thread
     * blocks while waiting for a retry delay, a permit or a slot, and a timeout completes the result with
     * {@link CallTimeoutException} without waiting for the call. A call that throws instead of returning
     * a stage, or that returns {@code null}, counts as a failed call.
     *
     * @param call starts the call to protect and returns its stage; called again by the retry, if there is one
     * @return a future that completes with the call's result, the fallback's, or the last failure
     *     (never wrapped in a {@code CompletionException})
     */
    public CompletableFuture<T> executeAsync(PipelineCall<? extends CompletionStage<T>, ?> call) {
        Objects.requireNonNull(call, "call");
        AsyncCall<T> next = call::call;
        BulkheadPolicy bulkhead = this.bulkhead;
        if (bulkhead != null) {
            AsyncCall<T> inner = next;
            next = () -> bulkhead.executeAsync(inner);
        }
        TimeoutPolicy timeout = this.timeout;
        if (timeout != null) {
            AsyncCall<T> inner = next;
            next = () -> timeout.executeAsync(inner);
        }
        RateLimiterPolicy rateLimiter = this.rateLimiter;
        if (rateLimiter != null) {
            AsyncCall<T> inner = next;
            next = () -> rateLimiter.executeAsync(inner);
        }
        CircuitBreakerPolicy circuitBreaker = this.circuitBreaker;
        if (circuitBreaker != null) {
            AsyncCall<T> inner = next;
            Classifier<? super T> classifier = circuitBreakerClassifier;
            next = classifier == null ? () -> circuitBreaker.executeAsync(inner)
                    : () -> circuitBreaker.executeAsync(inner, classifier);
        }
        RetryPolicy retry = this.retry;
        if (retry != null) {
            Classifier<? super T> classifier = retryClassifier;
            return classifier == null ? retry.executeAsync(next, fallback, recovery)
                    : retry.executeAsync(next, classifier, fallback, recovery);
        }
        FallbackPolicy fallback = this.fallback;
        Recovery<T> recovery = this.recovery;
        if (fallback != null && recovery != null) {
            return fallback.executeAsync(next, recovery);
        }
        return start(next);
    }

    /** Starts the outermost layer when no retry or fallback does it. */
    @SuppressWarnings("unchecked")
    private static <T> CompletableFuture<T> start(AsyncCall<T> call) {
        CompletionStage<T> stage;
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
        if (circuitBreaker != null) {
            text.append(", circuitBreaker");
        }
        if (rateLimiter != null) {
            text.append(", rateLimiter");
        }
        if (timeout != null) {
            text.append(", timeout");
        }
        if (bulkhead != null) {
            text.append(", bulkhead");
        }
        return text.append(']').toString();
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
        private @Nullable Step<CircuitBreakerConfig.Builder> circuitBreaker;
        private @Nullable Step<RateLimiterConfig.Builder> rateLimiter;
        private @Nullable Step<TimeoutConfig.Builder> timeout;
        private @Nullable Step<BulkheadConfig.Builder> bulkhead;
        private @Nullable Classifier<? super T> classifier;
        private @Nullable Classifier<? super T> retryClassifier;
        private @Nullable Classifier<? super T> circuitBreakerClassifier;
        private @Nullable FallbackHandler<? extends T> fallbackHandler;
        private List<Class<? extends Throwable>> fallbackApplyOn = List.of(Throwable.class);
        private List<Class<? extends Throwable>> fallbackSkipOn = List.of();

        private Builder(ResilienceRegistry registry, String id) {
            this.registry = Objects.requireNonNull(registry, "registry");
            this.id = Objects.requireNonNull(id, "id");
            if (id.isBlank()) {
                throw new IllegalArgumentException("The pipeline id must not be blank");
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
            return retry("", Objects.requireNonNull(overrides, "overrides"));
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
            retry = new Step<>(Objects.requireNonNull(config, "config"), overrides);
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
            return circuitBreaker("", Objects.requireNonNull(overrides, "overrides"));
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
            circuitBreaker = new Step<>(Objects.requireNonNull(config, "config"), overrides);
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
            return rateLimiter("", Objects.requireNonNull(overrides, "overrides"));
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
            rateLimiter = new Step<>(Objects.requireNonNull(config, "config"), overrides);
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
            return timeout("", Objects.requireNonNull(overrides, "overrides"));
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
            timeout = new Step<>(Objects.requireNonNull(config, "config"), overrides);
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
            return bulkhead("", Objects.requireNonNull(overrides, "overrides"));
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
            bulkhead = new Step<>(Objects.requireNonNull(config, "config"), overrides);
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
                throw new IllegalStateException("The classifier of pipeline '" + id + "' is set twice");
            }
            this.classifier = Objects.requireNonNull(classifier, "classifier");
            return this;
        }

        /**
         * Lets a classifier decide what the retry retries, like {@code @Retry(classifier = ...)}: only
         * {@code FAILURE} is retried; its verdict replaces {@code retryOn}, while {@code abortOn} still wins.
         * A result classified {@code FAILURE} is retried too, and returned if the retries give up on it. A
         * classifier that throws ends the retries with its exception.
         *
         * @param classifier decides whether each attempt is a success, a failure or ignored
         * @return this builder
         */
        public Builder<T> retryClassifier(Classifier<? super T> classifier) {
            if (retryClassifier != null) {
                throw new IllegalStateException("The retry classifier of pipeline '" + id + "' is set twice");
            }
            retryClassifier = Objects.requireNonNull(classifier, "classifier");
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
                throw new IllegalStateException("The circuit breaker classifier of pipeline '" + id + "' is set twice");
            }
            circuitBreakerClassifier = Objects.requireNonNull(classifier, "classifier");
            return this;
        }

        /**
         * Adds a fallback that computes an alternative result from the failure. It runs once retries give
         * up, for failures selected by {@link #fallbackApplyOn} and {@link #fallbackSkipOn} (by default,
         * every failure, including rejections by the other policies).
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
                throw new IllegalStateException("The fallback of pipeline '" + id + "' is set twice");
            }
            fallbackHandler = Objects.requireNonNull(handler, "handler");
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

        private void once(@Nullable Step<?> step, String kind) {
            if (step != null) {
                throw new IllegalStateException("The " + kind + " of pipeline '" + id + "' is set twice");
            }
        }

        /**
         * Resolves the configurations and obtains the policy instances from the registry. Can be called
         * again: pipelines built from the same builder share their instances.
         *
         * @return the pipeline
         * @throws IllegalArgumentException if a named configuration does not exist, or a configuration is invalid
         * @throws IllegalStateException if the id is already used by an instance with a different configuration
         */
        public Pipeline<T> build() {
            if ((fallbackApplyOn.size() != 1 || fallbackApplyOn.get(0) != Throwable.class || !fallbackSkipOn.isEmpty())
                    && fallbackHandler == null) {
                throw new IllegalStateException("Pipeline '" + id + "' selects fallback failures but has no fallback");
            }
            if (classifier != null && retry == null && circuitBreaker == null) {
                throw new IllegalStateException("Pipeline '" + id + "' has a classifier but no retry or circuit breaker");
            }
            if (retryClassifier != null && retry == null) {
                throw new IllegalStateException("Pipeline '" + id + "' has a retry classifier but no retry");
            }
            if (circuitBreakerClassifier != null && circuitBreaker == null) {
                throw new IllegalStateException("Pipeline '" + id + "' has a circuit breaker classifier but no circuit breaker");
            }
            return new Pipeline<>(this);
        }
    }
}
