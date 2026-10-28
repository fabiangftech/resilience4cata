package dev.resilience4cata.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Methods that return Reactor's {@code Mono}: generated as the same nested {@code executeAsync} calls
 * as a {@code CompletionStage} method, inside {@code Monos.defer}, with the delegate's (and a
 * fallback's) {@code Mono} subscribed through {@code Monos.stage}. Every compilation runs with
 * {@code -Xlint:all -Werror}, so the generated code is also checked to be warning-free.
 */
class MonoProcessorTest {

    private static final String MONOS = "dev.resilience4cata.reactor.generated.Monos";

    @TempDir
    Path workDir;

    private Compilation compile(String qualifiedName, String source) {
        return Compilation.compile(workDir, Map.of(qualifiedName, source));
    }

    private String generated(String source) {
        Compilation result = compile("demo.Client", source);
        assertThat(result.errors()).isEmpty();
        assertThat(result.diagnostics()).isEmpty();
        return result.generatedSource("demo.Client$Resilient");
    }

    // ---- the generated method

    @Test
    void aRetriedMonoMethodRunsThePoliciesInsideDefer() {
        assertThat(generated("""
                package demo;
                import dev.resilience4cata.annotation.Retry;
                import reactor.core.publisher.Mono;
                public interface Client {
                    @Retry
                    Mono<Integer> price(String sku);
                }
                """))
                .contains("public reactor.core.publisher.Mono<java.lang.Integer> price(java.lang.String p0) {")
                .contains("return " + MONOS + ".<java.lang.Integer>defer(r4c$subscriberContext -> this.retry0.executeAsync(() -> "
                        + MONOS + ".<java.lang.Integer>stage(this.delegate.price(p0), r4c$subscriberContext)));")
                .doesNotContain("r4c$recover");
    }

    @Test
    void everyPolicyNestsInTheDefaultOrder() {
        assertThat(generated("""
                package demo;
                import dev.resilience4cata.annotation.*;
                import reactor.core.publisher.Mono;
                public interface Client {
                    @Retry @CircuitBreaker @RateLimiter @AdaptiveLimit @Timeout("1s") @Bulkhead
                    Mono<String> name(String id);
                }
                """))
                .contains("return " + MONOS + ".<java.lang.String>defer(r4c$subscriberContext -> this.retry0.executeAsync(() -> "
                        + "this.circuitBreaker0.executeAsync(() -> this.rateLimiter0.executeAsync(() -> "
                        + "this.adaptiveLimit0.executeAsync(() -> this.timeout0.executeAsync(() -> "
                        + "this.bulkhead0.executeAsync(() -> " + MONOS + ".<java.lang.String>stage("
                        + "this.delegate.name(p0), r4c$subscriberContext))))))));");
    }

    @Test
    void policyOrderAppliesToMonoMethods() {
        assertThat(generated("""
                package demo;
                import dev.resilience4cata.annotation.*;
                import reactor.core.publisher.Mono;
                public interface Client {
                    @Retry @Timeout("1s") @PolicyOrder({Layer.TIMEOUT, Layer.RETRY})
                    Mono<String> name(String id);
                }
                """))
                .contains("defer(r4c$subscriberContext -> this.timeout0.executeAsync(() -> this.retry0.executeAsync(() -> ");
    }

    @Test
    void aHedgeWorksOnAMonoMethod() {
        assertThat(generated("""
                package demo;
                import dev.resilience4cata.annotation.Hedge;
                import reactor.core.publisher.Mono;
                public interface Client {
                    @Hedge(delay = "50ms")
                    Mono<String> name(String id);
                }
                """))
                .contains("defer(r4c$subscriberContext -> this.hedge0.executeAsync(() -> ");
    }

    @Test
    void aMonoOfVoidCompiles() {
        assertThat(generated("""
                package demo;
                import dev.resilience4cata.annotation.Retry;
                import reactor.core.publisher.Mono;
                public interface Client {
                    @Retry
                    Mono<Void> send(String message);
                }
                """)).contains(MONOS + ".<java.lang.Void>defer(");
    }

    @Test
    void aMethodThatDeclaresACheckedExceptionCompiles() {
        assertThat(generated("""
                package demo;
                import dev.resilience4cata.annotation.Retry;
                import java.io.IOException;
                import reactor.core.publisher.Mono;
                public interface Client {
                    @Retry
                    Mono<String> read(String path) throws IOException;
                }
                """)).contains("public reactor.core.publisher.Mono<java.lang.String> read(java.lang.String p0)"
                        + " throws java.io.IOException {");
    }

    @Test
    void wildcardMonosNeedNoWidening() {
        String source = generated("""
                package demo;
                import dev.resilience4cata.annotation.Fallback;
                import dev.resilience4cata.annotation.Retry;
                import reactor.core.publisher.Mono;
                public interface Client {
                    @Retry @Fallback(method = "none")
                    Mono<? extends Number> count(String id);
                    default Mono<? extends Integer> none(String id) { return Mono.just(0); }

                    @Retry
                    Mono<?> anything();
                }
                """);
        assertThat(source)
                .contains("public reactor.core.publisher.Mono<? extends java.lang.Number> count(java.lang.String p0) {")
                .contains(MONOS + ".<java.lang.Number>stage(this.delegate.count(p0), r4c$subscriberContext)")
                .contains(MONOS + ".<java.lang.Number>stage(this.delegate.none(p0), r4c$subscriberContext)")
                .contains("public reactor.core.publisher.Mono<?> anything() {")
                .contains(MONOS + ".<java.lang.Object>stage(this.delegate.anything(), r4c$subscriberContext)")
                .doesNotContain("widen");
    }

    @Test
    void aGenericMonoMethodCallsItsFallbackWithTypeArguments() {
        assertThat(generated("""
                package demo;
                import dev.resilience4cata.annotation.Fallback;
                import dev.resilience4cata.annotation.Retry;
                import java.util.List;
                import reactor.core.publisher.Mono;
                public interface Client {
                    @Retry @Fallback(method = "items")
                    <T> Mono<List<T>> items(Class<T> type);
                    default <T> Mono<List<T>> items(Class<T> type, Exception e) { return Mono.just(List.of()); }
                }
                """))
                .contains("public <T> reactor.core.publisher.Mono<java.util.List<T>> items(java.lang.Class<T> p0) {")
                .contains("this.<T>r4c$recover0(r4c$e, r4c$attempts, r4c$subscriberContext, p0)")
                .contains("private <T> java.util.concurrent.CompletionStage<java.util.List<T>> r4c$recover0("
                        + "java.lang.Throwable r4c$e, int r4c$attempts, reactor.util.context.ContextView r4c$subscriberContext,"
                        + " java.lang.Class<T> p0)")
                .contains("return " + MONOS + ".<java.util.List<T>>stage(this.delegate.<T>items(p0, r4c$f0), r4c$subscriberContext);");
    }

    @Test
    void aGenericInterfaceGetsTheMemberTypes() {
        Compilation result = Compilation.compile(workDir, Map.of("demo.Repository", """
                package demo;
                import dev.resilience4cata.annotation.Retry;
                import reactor.core.publisher.Mono;
                public interface Repository<T> {
                    @Retry
                    Mono<T> find(String id);
                }
                """, "demo.Users", """
                package demo;
                public interface Users extends Repository<String> {
                }
                """));
        assertThat(result.errors()).isEmpty();
        assertThat(result.generatedSource("demo.Repository$Resilient"))
                .contains("public reactor.core.publisher.Mono<T> find(java.lang.String p0) {")
                .contains(MONOS + ".<T>defer(");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theGeneratedMonoRunsTheCallOncePerSubscriptionAndRetries() throws Exception {
        Compilation result = Compilation.compile(workDir, Map.of("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.Fallback;
                import dev.resilience4cata.annotation.Retry;
                import reactor.core.publisher.Mono;
                public interface Client {
                    @Retry(maxAttempts = 3, delay = "1ms") @Fallback(method = "none")
                    Mono<String> name(String id);
                    default Mono<String> none(String id, Throwable failure) {
                        return Mono.deferContextual(c -> Mono.just("fallback " + c.getOrDefault("who", "?")));
                    }
                }
                """, "demo.Flaky", """
                package demo;
                import java.util.concurrent.atomic.AtomicInteger;
                import reactor.core.publisher.Mono;
                public final class Flaky implements Client {
                    public final AtomicInteger calls = new AtomicInteger();
                    public int failures;
                    @Override
                    public Mono<String> name(String id) {
                        calls.incrementAndGet();
                        return Mono.defer(() -> failures-- > 0 ? Mono.error(new IllegalStateException("down"))
                                : Mono.just("hello " + id));
                    }
                }
                """));
        assertThat(result.errors()).isEmpty();
        try (java.net.URLClassLoader loader = new java.net.URLClassLoader(new java.net.URL[] {result.classes().toUri().toURL()},
                getClass().getClassLoader())) {
            Class<Object> client = (Class<Object>) loader.loadClass("demo.Client");
            Object flaky = loader.loadClass("demo.Flaky").getConstructor().newInstance();
            java.util.concurrent.atomic.AtomicInteger calls = (java.util.concurrent.atomic.AtomicInteger)
                    flaky.getClass().getField("calls").get(flaky);
            java.lang.reflect.Field failures = flaky.getClass().getField("failures");
            Object resilient = dev.resilience4cata.Resilient.of(client, flaky,
                    dev.resilience4cata.ResilienceRegistry.builder().build());
            java.lang.reflect.Method name = client.getMethod("name", String.class);

            reactor.core.publisher.Mono<String> mono = (reactor.core.publisher.Mono<String>) name.invoke(resilient, "ana");
            assertThat(calls).as("nothing before a subscription").hasValue(0);
            assertThat(mono.block(java.time.Duration.ofSeconds(5))).isEqualTo("hello ana");
            assertThat(mono.block(java.time.Duration.ofSeconds(5))).as("a second subscription").isEqualTo("hello ana");
            assertThat(calls).hasValue(2);

            failures.setInt(flaky, 2);
            assertThat(mono.block(java.time.Duration.ofSeconds(5))).as("retried twice").isEqualTo("hello ana");
            assertThat(calls).hasValue(5);

            failures.setInt(flaky, 10);
            assertThat(mono.contextWrite(reactor.util.context.Context.of("who", "ctx")).block(java.time.Duration.ofSeconds(5)))
                    .as("the fallback's Mono sees the subscriber's context").isEqualTo("fallback ctx");
        }
    }

    // ---- fallbacks

    @Test
    void aFallbackMayReturnTheValueOrAMono() {
        String source = generated("""
                package demo;
                import dev.resilience4cata.annotation.Fallback;
                import dev.resilience4cata.annotation.Retry;
                import java.io.IOException;
                import reactor.core.publisher.Mono;
                public interface Client {
                    @Retry @Fallback(method = "zero")
                    Mono<Integer> value(String id);
                    default Integer zero(String id) { return 0; }

                    @Retry @Fallback(method = "cached")
                    Mono<Integer> mono(String id);
                    default Mono<Integer> cached(String id, Throwable failure) { return Mono.just(1); }

                    @Fallback(method = "byType")
                    Mono<String> typed(String id);
                    default Mono<String> byType(String id, IOException e) { return Mono.just("io"); }
                    default String byType(String id, IllegalStateException e) { return "state"; }
                }
                """);
        assertThat(source)
                .contains("return java.util.concurrent.CompletableFuture.completedFuture(this.delegate.zero(p0));")
                .contains("return " + MONOS + ".<java.lang.Integer>stage(this.delegate.cached(p0, r4c$e), r4c$subscriberContext);")
                // Without a retry the fallback is its own outermost layer, inside defer.
                .contains("return " + MONOS + ".<java.lang.String>defer(r4c$subscriberContext -> this.fallback4.executeAsync(() -> "
                        + MONOS + ".<java.lang.String>stage(this.delegate.typed(p0), r4c$subscriberContext), (r4c$e, r4c$attempts)"
                        + " -> this.r4c$recover4(r4c$e, r4c$attempts, r4c$subscriberContext, p0)));")
                .contains("if (r4c$e instanceof java.lang.IllegalStateException r4c$f0) {")
                .contains("    return java.util.concurrent.CompletableFuture.completedFuture(this.delegate.byType(p0, r4c$f0));")
                .contains("if (r4c$e instanceof java.io.IOException r4c$f1) {")
                .contains("    return " + MONOS + ".<java.lang.String>stage(this.delegate.byType(p0, r4c$f1), r4c$subscriberContext);")
                .contains("throw r4c$e;");
    }

    @Test
    void aFallbackHandlerMayReturnTheValueOrAMono() {
        String source = generated("""
                package demo;
                import dev.resilience4cata.FallbackContext;
                import dev.resilience4cata.FallbackHandler;
                import dev.resilience4cata.annotation.Fallback;
                import dev.resilience4cata.annotation.Retry;
                import reactor.core.publisher.Mono;
                public interface Client {
                    @Retry @Fallback(Value.class)
                    Mono<Integer> value(String id);

                    @Retry @Fallback(Reactive.class)
                    Mono<Integer> mono(String id);

                    final class Value implements FallbackHandler<Integer> {
                        @Override public Integer apply(FallbackContext context) { return 0; }
                    }

                    final class Reactive implements FallbackHandler<Mono<Integer>> {
                        @Override public Mono<Integer> apply(FallbackContext context) { return Mono.just(1); }
                    }
                }
                """);
        assertThat(source)
                .contains("return java.util.concurrent.CompletableFuture.completedFuture(this.fallbackHandler0.apply(r4c$context));")
                .contains("return " + MONOS + ".<java.lang.Integer>stage(this.fallbackHandler1.apply(r4c$context), r4c$subscriberContext);");
    }

    @Test
    void aFallbackThatReturnsAStageIsRejectedForAMonoMethod() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.FallbackContext;
                import dev.resilience4cata.FallbackHandler;
                import dev.resilience4cata.annotation.Fallback;
                import java.util.concurrent.CompletableFuture;
                import java.util.concurrent.CompletionStage;
                import reactor.core.publisher.Mono;
                public interface Client {
                    @Fallback(method = "stage")
                    Mono<Integer> sibling(String id);
                    default CompletionStage<Integer> stage(String id) { return CompletableFuture.completedFuture(0); }

                    @Fallback(Stage.class)
                    Mono<Integer> handled(String id);

                    final class Stage implements FallbackHandler<CompletionStage<Integer>> {
                        @Override public CompletionStage<Integer> apply(FallbackContext context) { return null; }
                    }
                }
                """);
        assertThat(result.errors())
                .anySatisfy(e -> assertThat(e).contains("which is neither java.lang.Integer nor a Mono of it"))
                .anySatisfy(e -> assertThat(e).contains("Implement FallbackHandler<java.lang.Integer> or"
                        + " FallbackHandler<reactor.core.publisher.Mono<java.lang.Integer>>"));
    }

    // ---- classifiers

    @Test
    void aClassifierSeesTheMonosValue() {
        assertThat(generated("""
                package demo;
                import dev.resilience4cata.Classifier;
                import dev.resilience4cata.annotation.CircuitBreaker;
                import dev.resilience4cata.annotation.ExhaustedResult;
                import dev.resilience4cata.annotation.Fallback;
                import dev.resilience4cata.annotation.Retry;
                import reactor.core.publisher.Mono;
                public interface Client {
                    @Retry(classifier = Blank.class, onExhaustedResult = ExhaustedResult.THROW) @CircuitBreaker(classifier = Blank.class)
                    @Fallback(method = "none")
                    Mono<String> name(String id);
                    default String none(String id) { return ""; }

                    final class Blank implements Classifier<String> {
                        @Override public Outcome classify(String result, Throwable failure) {
                            return failure != null || result.isBlank() ? Outcome.FAILURE : Outcome.SUCCESS;
                        }
                    }
                }
                """))
                .contains("this.retry0.executeAsync(() -> this.circuitBreaker0.executeAsync(() -> " + MONOS
                        + ".<java.lang.String>stage(this.delegate.name(p0), r4c$subscriberContext), this.retryClassifier0, true),"
                        + " this.retryClassifier0, true, this.fallback0, (r4c$e, r4c$attempts) -> this.r4c$recover0(");
    }

    @Test
    void aClassifierOfAnotherTypeIsRejected() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.Classifier;
                import dev.resilience4cata.annotation.Retry;
                import reactor.core.publisher.Mono;
                public interface Client {
                    @Retry(classifier = Numbers.class)
                    Mono<String> name(String id);

                    final class Numbers implements Classifier<Integer> {
                        @Override public Outcome classify(Integer result, Throwable failure) { return Outcome.SUCCESS; }
                    }
                }
                """);
        assertThat(result.errorCodes()).containsExactly("R4C-CLS-010");
    }

    // ---- annotated classes

    @Test
    void anAnnotatedClassOverridesItsMonoMethodsAndCallsSuper() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.Fallback;
                import dev.resilience4cata.annotation.Retry;
                import reactor.core.publisher.Mono;
                public class Client {
                    @Retry @Fallback(method = "none")
                    public Mono<String> name(String id, int context) { return Mono.just(id); }
                    protected Mono<String> none(String id, int context) { return Mono.empty(); }

                    public Mono<String> plain(String id) { return Mono.just(id); }

                    public Mono<String> self(String id) { return name(id, 0); }
                }
                """);
        assertThat(result.errors()).isEmpty();
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.generatedSource("demo.Client$Resilient"))
                .contains("public class Client$Resilient extends demo.Client {")
                .contains("public reactor.core.publisher.Mono<java.lang.String> name(java.lang.String id, int context) {")
                .contains(MONOS + ".<java.lang.String>stage(super.name(id, context), r4c$subscriberContext)")
                .contains(MONOS + ".<java.lang.String>stage(this.none(id, context), r4c$subscriberContext)")
                .doesNotContain("plain(")
                .doesNotContain("self(");
    }

    // ---- what is still rejected, and what stays unchanged

    @Test
    void aMonoOfSuperOrARawMonoIsAnUnsupportedAsyncType() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.Retry;
                import reactor.core.publisher.Mono;
                public interface Client {
                    @Retry
                    Mono<? super Integer> lower();

                    @SuppressWarnings("rawtypes")
                    @Retry
                    Mono raw();
                }
                """);
        assertThat(result.errorCodes()).containsExactly("R4C-ASY-001", "R4C-ASY-001");
        assertThat(result.errors()).allSatisfy(e -> assertThat(e).contains("(or Mono<T>, with resilience4cata-reactor)"));
    }

    @Test
    void aFluxIsStillAReactiveTypeError() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.Retry;
                import reactor.core.publisher.Flux;
                public interface Client {
                    @Retry
                    Flux<Integer> prices();
                }
                """);
        assertThat(result.errorCodes()).containsExactly("R4C-ASY-002");
        assertThat(result.errors()).singleElement().asString()
                .contains("a org.reactivestreams.Publisher")
                .contains("Reactor's Mono<T> with resilience4cata-reactor (for a Flux, flux.collectList())");
    }

    @Test
    void aMonoMethodWithoutTheReactorModuleIsAnError() {
        Compilation result = Compilation.compileWithoutReactor(workDir, Map.of("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.Retry;
                import reactor.core.publisher.Mono;
                public interface Client {
                    @Retry
                    Mono<Integer> price(String sku);

                    @Retry
                    Mono<String> name(String id);
                }
                """));
        assertThat(result.errorCodes()).containsExactly("R4C-ASY-003", "R4C-ASY-003");
        assertThat(result.errors()).first().asString()
                .contains("The method returns reactor.core.publisher.Mono<java.lang.Integer>")
                .contains("resilience4cata-reactor");
    }

    @Test
    void anUnannotatedMonoMethodNeedsNoReactorModule() {
        Compilation result = Compilation.compileWithoutReactor(workDir, Map.of("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.Retry;
                import reactor.core.publisher.Mono;
                public interface Client {
                    @Retry
                    String name(String id);

                    Mono<Integer> price(String sku);
                }
                """));
        assertThat(result.errors()).isEmpty();
        assertThat(result.generatedSource("demo.Client$Resilient"))
                .contains("public reactor.core.publisher.Mono<java.lang.Integer> price(java.lang.String p0) {\n"
                        + "        return this.delegate.price(p0);")
                .doesNotContain("Monos");
    }

    @Test
    void stageMethodsNextToMonoMethodsAreGeneratedAsBefore() {
        String stageOnly = generated("""
                package demo;
                import dev.resilience4cata.annotation.Fallback;
                import dev.resilience4cata.annotation.Retry;
                import java.util.concurrent.CompletableFuture;
                import java.util.concurrent.CompletionStage;
                public interface Client {
                    @Retry @Fallback(method = "zero")
                    CompletionStage<Integer> count(String id);
                    default Integer zero(String id) { return 0; }
                }
                """);
        String both = Compilation.compile(workDir.resolve("both"), Map.of("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.Fallback;
                import dev.resilience4cata.annotation.Retry;
                import java.util.concurrent.CompletableFuture;
                import java.util.concurrent.CompletionStage;
                import reactor.core.publisher.Mono;
                public interface Client {
                    @Retry @Fallback(method = "zero")
                    CompletionStage<Integer> count(String id);
                    default Integer zero(String id) { return 0; }

                    @Retry
                    Mono<Integer> mono(String id);
                }
                """)).generatedSource("demo.Client$Resilient");
        String countMethod = stageOnly.substring(stageOnly.indexOf("    @Override\n    public java.util.concurrent.CompletionStage"),
                stageOnly.indexOf("    @Override\n    public java.lang.Integer zero"));
        assertThat(both).contains(countMethod);
        assertThat(countMethod).doesNotContain("Monos").doesNotContain("r4c$subscriberContext");
    }
}
