package dev.resilience4cata.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code @Hedge}: the async layer in Retry's slot, and its compile errors. */
class HedgeProcessorTest {

    @TempDir
    Path workDir;

    private Compilation compile(String qualifiedName, String source) {
        return Compilation.compile(workDir, Map.of(qualifiedName, source));
    }

    @Test
    void generatesTheHedgeAsTheOutermostAsyncLayer() {
        Compilation result = compile("demo.QuoteApi", """
                package demo;
                import dev.resilience4cata.annotation.Bulkhead;
                import dev.resilience4cata.annotation.CircuitBreaker;
                import dev.resilience4cata.annotation.Fallback;
                import dev.resilience4cata.annotation.Hedge;
                import dev.resilience4cata.annotation.RateLimiter;
                import dev.resilience4cata.annotation.Timeout;
                import java.util.concurrent.CompletableFuture;
                import java.util.concurrent.CompletionStage;
                public interface QuoteApi {
                    @Hedge(delay = "50ms", maxAttempts = 3, hedgeOn = java.io.IOException.class,
                            abortOn = IllegalArgumentException.class, budgetPercent = 5, budgetMinHedgesPerSecond = 2,
                            budgetWindow = "30s")
                    @CircuitBreaker(name = "quotes") @RateLimiter(permits = 10) @Timeout("500ms") @Bulkhead(maxConcurrent = 4)
                    @Fallback(method = "cached")
                    CompletableFuture<String> quote(String sku);
                    default CompletableFuture<String> cached(String sku, Throwable e) {
                        return CompletableFuture.completedFuture("cached");
                    }
                    @Hedge(delay = "0", name = "shared")
                    CompletionStage<Integer> count();
                }
                """);

        assertThat(result.errors()).isEmpty();
        String generated = result.generatedSource("demo.QuoteApi$Resilient");
        assertThat(generated)
                .contains("private final dev.resilience4cata.generated.HedgePolicy hedge0;")
                .contains("this.hedge0 = registry.hedgePolicy(\"demo.QuoteApi#quote(java.lang.String)\", \"\", \"\", r4c$b -> {")
                .contains("r4c$b.delay(java.time.Duration.ofNanos(50000000L));")
                .contains("r4c$b.maxAttempts(3);")
                .contains("r4c$b.hedgeOn(java.util.List.of(java.io.IOException.class));")
                .contains("r4c$b.abortOn(java.util.List.of(java.lang.IllegalArgumentException.class));")
                .contains("r4c$b.budgetPercent(5.0);")
                .contains("r4c$b.budgetMinHedgesPerSecond(2);")
                .contains("r4c$b.budgetWindow(java.time.Duration.ofNanos(30000000000L));")
                .contains("return this.hedge0.executeAsync(() -> this.circuitBreaker0.executeAsync(() -> "
                        + "this.rateLimiter0.executeAsync(() -> this.timeout0.executeAsync(() -> "
                        + "this.bulkhead0.executeAsync(() -> this.delegate.quote(p0))))), this.fallback0, "
                        + "(r4c$e, r4c$attempts) -> this.r4c$recover0(r4c$e, r4c$attempts, p0));")
                .doesNotContain("this.fallback0.executeAsync")
                .contains("this.hedge2 = registry.hedgePolicy(\"demo.QuoteApi#count()\", \"shared\", \"\", r4c$b -> {")
                .contains("r4c$b.delay(java.time.Duration.ofNanos(0L));")
                .contains("return this.hedge2.executeAsync(() -> this.delegate.count());");
    }

    @Test
    void worksOnAnnotatedClassesAndWildcardStages() {
        Compilation result = compile("demo.Catalog", """
                package demo;
                import dev.resilience4cata.annotation.Hedge;
                import java.util.concurrent.CompletableFuture;
                import java.util.concurrent.CompletionStage;
                public class Catalog {
                    @Hedge(delay = "20ms")
                    public CompletionStage<? extends Number> size() {
                        return CompletableFuture.completedFuture(1);
                    }
                }
                """);

        assertThat(result.errors()).isEmpty();
        assertThat(result.generatedSource("demo.Catalog$Resilient"))
                .contains("return this.hedge0.executeAsync(() -> "
                        + "dev.resilience4cata.generated.AsyncCall.<java.lang.Number>widen(super.size()));");
    }

    @Test
    void aHedgeOnASynchronousMethodIsAnError() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.Hedge;
                import java.util.concurrent.CompletableFuture;
                @Hedge(delay = "50ms")
                public interface Client {
                    String find(String id);
                    int count();
                    CompletableFuture<String> findAsync(String id);
                }
                """);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCodes()).containsExactly("R4C-HDG-001", "R4C-HDG-001");
        assertThat(result.errors())
                .anySatisfy(e -> assertThat(e).contains("@Hedge needs an asynchronous method")
                        .contains("find returns java.lang.String; return java.util.concurrent.CompletableFuture<java.lang.String>"))
                .anySatisfy(e -> assertThat(e).contains("CompletableFuture<java.lang.Integer>")
                        .contains("annotate the async methods instead of the type"));
    }

    @Test
    void hedgeAndRetryOnOneMethodIsAnErrorHoweverTheyArrive() {
        Compilation result = Compilation.compile(workDir, Map.of("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.Hedge;
                import dev.resilience4cata.annotation.Retry;
                import java.util.concurrent.CompletableFuture;
                public interface Client {
                    @Hedge @Retry
                    CompletableFuture<String> direct();
                    @Hedging @Retry
                    CompletableFuture<String> bundled();
                }
                """, "demo.Hedging", """
                package demo;
                import dev.resilience4cata.annotation.Hedge;
                import dev.resilience4cata.annotation.ResiliencePolicy;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;
                @ResiliencePolicy
                @Hedge(delay = "20ms")
                @Retention(RetentionPolicy.CLASS)
                @Target(ElementType.METHOD)
                public @interface Hedging {
                }
                """, "demo.TypeLevel", """
                package demo;
                import dev.resilience4cata.annotation.Hedge;
                import dev.resilience4cata.annotation.Retry;
                import java.util.concurrent.CompletableFuture;
                @Retry
                public interface TypeLevel {
                    @Hedge
                    CompletableFuture<String> find();
                }
                """));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCodes()).containsExactly("R4C-HDG-002", "R4C-HDG-002", "R4C-HDG-002");
        assertThat(result.errors()).allSatisfy(e -> assertThat(e).contains("@Hedge and @Retry cannot be combined")
                .contains("Keep one"));
    }

    @Test
    void rejectsInvalidHedgeValues() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.Hedge;
                import java.util.concurrent.CompletableFuture;
                public interface Client {
                    @Hedge(maxAttempts = 1)
                    CompletableFuture<String> one();
                    @Hedge(maxAttempts = 11)
                    CompletableFuture<String> eleven();
                    @Hedge(delay = "soon")
                    CompletableFuture<String> soon();
                    @Hedge(budgetPercent = -1.5, budgetMinHedgesPerSecond = -3, budgetWindow = "100ms")
                    CompletableFuture<String> budget();
                    @Hedge(enabled = "yes")
                    CompletableFuture<String> enabled();
                }
                """);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCodes()).containsExactlyInAnyOrder("R4C-ATR-008", "R4C-ATR-008", "R4C-ATR-001",
                "R4C-ATR-007", "R4C-ATR-007", "R4C-ATR-007", "R4C-ATR-006");
        assertThat(result.errors())
                .anySatisfy(e -> assertThat(e).contains("@Hedge.maxAttempts must be between 2 and 10, was 1"))
                .anySatisfy(e -> assertThat(e).contains("@Hedge.maxAttempts must be between 2 and 10, was 11"))
                .anySatisfy(e -> assertThat(e).contains("@Hedge.budgetMinHedgesPerSecond must not be negative, was -3"));
    }

    @Test
    void methodsWithoutAHedgeGenerateTheSameCodeAsBefore() {
        String withoutHedge = """
                package demo;
                import dev.resilience4cata.annotation.CircuitBreaker;
                import dev.resilience4cata.annotation.Fallback;
                import dev.resilience4cata.annotation.Retry;
                import dev.resilience4cata.annotation.Timeout;
                import java.util.concurrent.CompletableFuture;
                public interface Client {
                    @Retry @CircuitBreaker @Timeout("1s") @Fallback(method = "recover")
                    CompletableFuture<String> find(String id);
                    default CompletableFuture<String> recover(String id) { return CompletableFuture.completedFuture(id); }
                    @Retry(maxAttempts = 2) @Fallback(method = "recoverSync")
                    String sync(String id);
                    default String recoverSync(String id) { return id; }
                }
                """;
        String plain = compile("demo.Client", withoutHedge).generatedSource("demo.Client$Resilient");
        Compilation hedged = Compilation.compile(workDir.resolve("hedged"), Map.of("demo.Client",
                withoutHedge.replace("    default String recoverSync(String id) { return id; }\n",
                        "    default String recoverSync(String id) { return id; }\n"
                        + "    @dev.resilience4cata.annotation.Hedge CompletableFuture<String> extra();\n")));

        assertThat(hedged.errors()).isEmpty();
        List<String> lines = hedged.generatedSource("demo.Client$Resilient").lines().toList();
        int extra = lines.indexOf("    public java.util.concurrent.CompletableFuture<java.lang.String> extra() {");
        assertThat(extra).isPositive();
        List<String> withoutTheHedgedMethod = new java.util.ArrayList<>(lines);
        withoutTheHedgedMethod.subList(extra - 2, extra + 3).clear(); // blank line, @Override, method, body, brace
        withoutTheHedgedMethod.removeIf(line -> line.contains("hedge4") || line.contains("Client#extra()"));
        assertThat(withoutTheHedgedMethod).as("only the hedged method adds lines").isEqualTo(plain.lines().toList());
    }
}
