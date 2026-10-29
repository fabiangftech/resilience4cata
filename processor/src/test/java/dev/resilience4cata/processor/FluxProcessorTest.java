package dev.resilience4cata.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Methods that return Reactor's {@code Flux}: the policies run inside {@code Fluxes.defer}, the layers
 * inside the retry or fallback are one attempt ({@code r4c$stream.attempt}), the timeout, breaker and
 * adaptive limit use their streaming form, and the delegate's {@code Flux} is subscribed by
 * {@code r4c$stream.subscribe}. Every compilation runs with {@code -Xlint:all -Werror}, so the generated
 * code is also checked to be warning-free.
 */
class FluxProcessorTest {

    private static final String FLUXES = "dev.resilience4cata.reactor.generated.Fluxes";

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
    void aRetriedFluxMethodRunsEachAttemptInsideTheBoundary() {
        assertThat(generated("""
                package demo;
                import dev.resilience4cata.annotation.Retry;
                import reactor.core.publisher.Flux;
                public interface Client {
                    @Retry
                    Flux<Integer> prices(String sku);
                }
                """))
                .contains("public reactor.core.publisher.Flux<java.lang.Integer> prices(java.lang.String p0) {")
                .contains("return " + FLUXES + ".<java.lang.Integer>defer(r4c$stream -> this.retry0.executeAsync("
                        + "() -> r4c$stream.attempt(() -> r4c$stream.subscribe(this.delegate.prices(p0)))));")
                .doesNotContain("r4c$recover");
    }

    @Test
    void everyPolicyNestsInTheDefaultOrderWithTheStreamingForms() {
        assertThat(generated("""
                package demo;
                import dev.resilience4cata.annotation.*;
                import reactor.core.publisher.Flux;
                public interface Client {
                    @Retry @CircuitBreaker @RateLimiter @AdaptiveLimit @Timeout("1s") @Bulkhead
                    Flux<String> names(String id);
                }
                """))
                .contains("return " + FLUXES + ".<java.lang.String>defer(r4c$stream -> this.retry0.executeAsync(() -> "
                        + "r4c$stream.attempt(() -> this.circuitBreaker0.executeStreamAsync(() -> "
                        + "this.rateLimiter0.executeAsync(() -> this.adaptiveLimit0.executeStreamAsync(() -> "
                        + "this.timeout0.executeStreamAsync(() -> this.bulkhead0.executeAsync(() -> "
                        + "r4c$stream.subscribe(this.delegate.names(p0))), r4c$stream), r4c$stream)), r4c$stream))));");
    }

    @Test
    void withoutARetryOrAFallbackThereIsNoBoundary() {
        assertThat(generated("""
                package demo;
                import dev.resilience4cata.annotation.Timeout;
                import reactor.core.publisher.Flux;
                public interface Client {
                    @Timeout("2s")
                    Flux<String> ticks();
                }
                """))
                .contains("return " + FLUXES + ".<java.lang.String>defer(r4c$stream -> this.timeout0.executeStreamAsync("
                        + "() -> r4c$stream.subscribe(this.delegate.ticks()), r4c$stream));")
                .doesNotContain("attempt(");
    }

    @Test
    void aFluxOfVoidAndAMethodThatDeclaresACheckedExceptionCompile() {
        assertThat(generated("""
                package demo;
                import dev.resilience4cata.annotation.Retry;
                import java.io.IOException;
                import reactor.core.publisher.Flux;
                public interface Client {
                    @Retry
                    Flux<Void> pings();

                    @Retry
                    Flux<String> lines(String path) throws IOException;
                }
                """))
                .contains(FLUXES + ".<java.lang.Void>defer(")
                .contains("public reactor.core.publisher.Flux<java.lang.String> lines(java.lang.String p0) throws java.io.IOException {");
    }

    @Test
    void wildcardFluxesNeedNoWidening() {
        String source = generated("""
                package demo;
                import dev.resilience4cata.annotation.Fallback;
                import dev.resilience4cata.annotation.Retry;
                import reactor.core.publisher.Flux;
                public interface Client {
                    @Retry @Fallback(method = "none")
                    Flux<? extends Number> counts(String id);
                    default Flux<? extends Integer> none(String id) { return Flux.empty(); }

                    @Retry
                    Flux<?> anything();
                }
                """);
        assertThat(source)
                .contains(FLUXES + ".<java.lang.Number>defer(")
                .contains(FLUXES + ".<java.lang.Object>defer(")
                .contains("return r4c$stream.recover(this.delegate.none(p0));")
                .doesNotContain("widen");
    }

    @Test
    void aGenericFluxMethodCallsItsFallbackWithTypeArguments() {
        assertThat(generated("""
                package demo;
                import dev.resilience4cata.annotation.Fallback;
                import dev.resilience4cata.annotation.Retry;
                import reactor.core.publisher.Flux;
                public interface Client {
                    @Retry @Fallback(method = "none")
                    <T> Flux<T> all(Class<T> type);
                    default <T> Flux<T> none(Class<T> type) { return Flux.empty(); }
                }
                """))
                .contains("this.<T>r4c$recover0(r4c$e, r4c$attempts, r4c$stream, p0)")
                .contains("private <T> java.util.concurrent.CompletionStage<java.lang.Void> r4c$recover0(java.lang.Throwable r4c$e,"
                        + " int r4c$attempts, dev.resilience4cata.reactor.generated.FluxCall<T> r4c$stream,"
                        + " java.lang.Class<T> p0) throws java.lang.Throwable {")
                .contains("return r4c$stream.recover(this.delegate.<T>none(p0));");
    }

    // ---- fallbacks

    @Test
    void aFallbackMayReturnAFluxOrOneValue() {
        String source = generated("""
                package demo;
                import dev.resilience4cata.annotation.Fallback;
                import dev.resilience4cata.annotation.Retry;
                import java.io.IOException;
                import reactor.core.publisher.Flux;
                public interface Client {
                    @Retry @Fallback(method = "cached")
                    Flux<String> names(String id);
                    default Flux<String> cached(String id, IOException failure) { return Flux.just("a", "b"); }
                    default String cached(String id, Throwable failure) { return "unknown"; }
                }
                """);
        assertThat(source)
                .contains("this.retry0.executeAsync(() -> r4c$stream.attempt(() -> r4c$stream.subscribe(this.delegate.names(p0))),"
                        + " this.fallback0, (r4c$e, r4c$attempts) -> this.r4c$recover0(r4c$e, r4c$attempts, r4c$stream, p0))")
                .contains("if (r4c$e instanceof java.io.IOException r4c$f0) {")
                .contains("    return r4c$stream.recover(this.delegate.cached(p0, r4c$f0));")
                .contains("return r4c$stream.recoverValue(this.delegate.cached(p0, r4c$e));");
    }

    @Test
    void aFallbackWithoutARetryIsItsOwnOutermostLayerAroundOneAttempt() {
        assertThat(generated("""
                package demo;
                import dev.resilience4cata.annotation.Fallback;
                import dev.resilience4cata.annotation.Timeout;
                import reactor.core.publisher.Flux;
                public interface Client {
                    @Timeout("1s") @Fallback(method = "none")
                    Flux<String> names(String id);
                    default Flux<String> none(String id) { return Flux.empty(); }
                }
                """))
                .contains("defer(r4c$stream -> this.fallback0.executeAsync(() -> r4c$stream.attempt(() -> "
                        + "this.timeout0.executeStreamAsync(() -> r4c$stream.subscribe(this.delegate.names(p0)), r4c$stream)), "
                        + "(r4c$e, r4c$attempts) -> this.r4c$recover0(r4c$e, r4c$attempts, r4c$stream, p0)));");
    }

    @Test
    void aFallbackHandlerMayReturnAFluxOrOneValue() {
        Compilation result = Compilation.compile(workDir, Map.of("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.Fallback;
                import reactor.core.publisher.Flux;
                public interface Client {
                    @Fallback(Many.class)
                    Flux<String> many(String id);
                    @Fallback(One.class)
                    Flux<String> one(String id);
                }
                """, "demo.Many", """
                package demo;
                import dev.resilience4cata.FallbackContext;
                import dev.resilience4cata.FallbackHandler;
                import reactor.core.publisher.Flux;
                public final class Many implements FallbackHandler<Flux<String>> {
                    @Override
                    public Flux<String> apply(FallbackContext context) { return Flux.just("x"); }
                }
                """, "demo.One", """
                package demo;
                import dev.resilience4cata.FallbackContext;
                import dev.resilience4cata.FallbackHandler;
                public final class One implements FallbackHandler<String> {
                    @Override
                    public String apply(FallbackContext context) { return "x"; }
                }
                """));
        assertThat(result.errors()).isEmpty();
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.generatedSource("demo.Client$Resilient"))
                .contains("return r4c$stream.recover(this.fallbackHandler0.apply(r4c$context));")
                .contains("return r4c$stream.recoverValue(this.fallbackHandler1.apply(r4c$context));");
    }

    @Test
    void aFallbackThatReturnsAStageOrAMonoIsRejectedForAFluxMethod() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.Fallback;
                import java.util.concurrent.CompletableFuture;
                import java.util.concurrent.CompletionStage;
                import reactor.core.publisher.Flux;
                import reactor.core.publisher.Mono;
                public interface Client {
                    @Fallback(method = "stage")
                    Flux<String> a(String id);
                    default CompletionStage<String> stage(String id) { return CompletableFuture.completedFuture(id); }

                    @Fallback(method = "mono")
                    Flux<String> b(String id);
                    default Mono<String> mono(String id) { return Mono.just(id); }
                }
                """);
        assertThat(result.errors()).hasSize(2).allSatisfy(error -> assertThat(error).contains("nor a Flux of it"));
    }

    // ---- annotated classes

    @Test
    void anAnnotatedClassOverridesItsFluxMethodsAndCallsSuper() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.Fallback;
                import dev.resilience4cata.annotation.Retry;
                import reactor.core.publisher.Flux;
                public class Client {
                    @Retry @Fallback(method = "none")
                    public Flux<String> names(String id, int page) { return Flux.just(id); }
                    protected Flux<String> none(String id, int page) { return Flux.empty(); }

                    public Flux<String> plain(String id) { return Flux.just(id); }
                }
                """);
        assertThat(result.errors()).isEmpty();
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.generatedSource("demo.Client$Resilient"))
                .contains("public reactor.core.publisher.Flux<java.lang.String> names(java.lang.String id, int page) {")
                .contains("r4c$stream.subscribe(super.names(id, page))")
                .contains("return r4c$stream.recover(this.none(id, page));")
                .doesNotContain("plain(");
    }

    // ---- what a Flux method cannot use

    @Test
    void aHedgeOnAFluxMethodIsAnError() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.Hedge;
                import reactor.core.publisher.Flux;
                public interface Client {
                    @Hedge(delay = "50ms")
                    Flux<String> names(String id);
                }
                """);
        assertThat(result.errorCodes()).containsExactly("R4C-ASY-004");
        assertThat(result.errors()).singleElement().asString()
                .contains("names returns reactor.core.publisher.Flux<java.lang.String>, and a Flux method cannot use @Hedge");
    }

    @Test
    void aClassifierOnAFluxMethodIsAnError() {
        Compilation result = Compilation.compile(workDir, Map.of("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.CircuitBreaker;
                import dev.resilience4cata.annotation.Retry;
                import reactor.core.publisher.Flux;
                public interface Client {
                    @Retry(classifier = Blank.class) @CircuitBreaker(classifier = Blank.class)
                    Flux<String> names(String id);
                }
                """, "demo.Blank", """
                package demo;
                import dev.resilience4cata.Classifier;
                import org.jspecify.annotations.Nullable;
                public final class Blank implements Classifier<String> {
                    @Override
                    public Outcome classify(@Nullable String result, @Nullable Throwable failure) {
                        return failure != null ? Outcome.FAILURE : Outcome.SUCCESS;
                    }
                }
                """));
        assertThat(result.errorCodes()).containsExactly("R4C-ASY-004");
        assertThat(result.errors()).singleElement().asString()
                .contains("a classifier on @Retry")
                .contains("nor a classifier on @CircuitBreaker");
    }

    @Test
    void aPolicyOrderThatChangesTheOrderIsAnErrorAndTheDefaultOrderIsFine() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.*;
                import reactor.core.publisher.Flux;
                public interface Client {
                    @Retry @Timeout("1s") @PolicyOrder({Layer.TIMEOUT, Layer.RETRY})
                    Flux<String> moved(String id);

                    @Retry @Timeout("1s") @PolicyOrder({Layer.RETRY, Layer.TIMEOUT})
                    Flux<String> asBefore(String id);
                }
                """);
        assertThat(result.errorCodes()).containsExactly("R4C-ASY-004");
        assertThat(result.errors()).singleElement().asString().contains("moved returns").contains("@PolicyOrder");
    }

    @Test
    void aFluxOfSuperOrARawFluxIsAnUnsupportedAsyncType() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.Retry;
                import reactor.core.publisher.Flux;
                public interface Client {
                    @Retry
                    Flux<? super Integer> lower();

                    @SuppressWarnings("rawtypes")
                    @Retry
                    Flux raw();
                }
                """);
        assertThat(result.errorCodes()).containsExactly("R4C-ASY-001", "R4C-ASY-001");
    }

    @Test
    void aFluxMethodWithoutTheReactorModuleIsAnError() {
        Compilation result = Compilation.compileWithoutReactor(workDir, Map.of("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.Retry;
                import reactor.core.publisher.Flux;
                public interface Client {
                    @Retry
                    Flux<Integer> prices(String sku);
                }
                """));
        assertThat(result.errorCodes()).containsExactly("R4C-ASY-003");
        assertThat(result.errors()).singleElement().asString()
                .contains("Flux methods need resilience4cata-reactor 0.11.0 or newer");
    }

    @Test
    void stageAndMonoMethodsNextToFluxMethodsAreGeneratedAsBefore() {
        String without = generated("""
                package demo;
                import dev.resilience4cata.annotation.Retry;
                import java.util.concurrent.CompletionStage;
                import reactor.core.publisher.Mono;
                public interface Client {
                    @Retry
                    CompletionStage<Integer> count(String id);
                    @Retry
                    Mono<Integer> price(String id);
                }
                """);
        String with = Compilation.compile(workDir.resolve("with"), Map.of("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.Retry;
                import java.util.concurrent.CompletionStage;
                import reactor.core.publisher.Flux;
                import reactor.core.publisher.Mono;
                public interface Client {
                    @Retry
                    CompletionStage<Integer> count(String id);
                    @Retry
                    Mono<Integer> price(String id);
                    @Retry
                    Flux<Integer> prices(String id);
                }
                """)).generatedSource("demo.Client$Resilient");
        for (String method : List.of("count(java.lang.String p0) {", "price(java.lang.String p0) {")) {
            String expected = without.substring(without.indexOf(method), without.indexOf("    }\n", without.indexOf(method)));
            assertThat(with).contains(expected);
        }
    }

    // ---- the generated code runs

    @Test
    @SuppressWarnings("unchecked")
    void theGeneratedFluxRetriesBeforeTheFirstElementOnlyAndFallsBack() throws Exception {
        Compilation result = Compilation.compile(workDir, Map.of("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.Fallback;
                import dev.resilience4cata.annotation.Retry;
                import reactor.core.publisher.Flux;
                public interface Client {
                    @Retry(maxAttempts = 3, delay = "1ms") @Fallback(method = "none")
                    Flux<String> names(String id);
                    default Flux<String> none(String id, Throwable failure) {
                        return Flux.deferContextual(c -> Flux.just("fallback " + c.getOrDefault("who", "?")));
                    }
                }
                """, "demo.Flaky", """
                package demo;
                import java.util.concurrent.atomic.AtomicInteger;
                import reactor.core.publisher.Flux;
                public final class Flaky implements Client {
                    public final AtomicInteger calls = new AtomicInteger();
                    public int failures;
                    public boolean breakAfterFirst;
                    @Override
                    public Flux<String> names(String id) {
                        calls.incrementAndGet();
                        return Flux.defer(() -> failures-- > 0 ? Flux.error(new IllegalStateException("down"))
                                : breakAfterFirst ? Flux.just(id).concatWith(Flux.error(new IllegalStateException("reset")))
                                : Flux.just(id, id + "!"));
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
            Object resilient = dev.resilience4cata.Resilient.of(client, flaky,
                    dev.resilience4cata.ResilienceRegistry.builder().build());
            reactor.core.publisher.Flux<String> flux = (reactor.core.publisher.Flux<String>)
                    client.getMethod("names", String.class).invoke(resilient, "ana");
            java.time.Duration wait = java.time.Duration.ofSeconds(5);

            assertThat(calls).as("nothing before a subscription").hasValue(0);
            assertThat(flux.collectList().block(wait)).containsExactly("ana", "ana!");

            flaky.getClass().getField("failures").setInt(flaky, 2);
            assertThat(flux.collectList().block(wait)).as("retried twice").containsExactly("ana", "ana!");
            assertThat(calls).hasValue(4);

            flaky.getClass().getField("failures").setInt(flaky, 10);
            assertThat(flux.contextWrite(reactor.util.context.Context.of("who", "ctx")).collectList().block(wait))
                    .as("the fallback's Flux sees the subscriber's context").containsExactly("fallback ctx");

            flaky.getClass().getField("failures").setInt(flaky, 0);
            flaky.getClass().getField("breakAfterFirst").setBoolean(flaky, true);
            List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
            List<String> failures = new java.util.concurrent.CopyOnWriteArrayList<>();
            flux.doOnNext(seen::add).onErrorResume(failure -> {
                failures.add(failure.getMessage());
                return reactor.core.publisher.Flux.empty();
            }).blockLast(wait);
            assertThat(seen).containsExactly("ana");
            assertThat(failures).containsExactly("reset");
            assertThat(calls).as("a failure after the first element is not retried").hasValue(8);
        }
    }
}
