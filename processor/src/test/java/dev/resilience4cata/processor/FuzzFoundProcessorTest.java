package dev.resilience4cata.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Cases that {@link ProcessorFuzzTest} found, reduced to the smallest source that showed each of them. */
class FuzzFoundProcessorTest {

    @TempDir
    Path workDir;

    private static final String HOLDER = """
            package demo;
            public class Holder<V> {
                public class Inner {
                }
            }
            """;

    @Test
    void printsAnInnerClassOfAGenericClassWithItsOuterTypeArguments() {
        // TypePrinter printed demo.Holder.Inner, a raw type: a rawtypes warning, and a name clash in a subclass
        Compilation result = Compilation.compile(workDir, Map.of("demo.Holder", HOLDER, "demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.Timeout;
                public interface Client {
                    @Timeout("1s")
                    Holder<String>.Inner find(Holder<Integer>.Inner key);
                }
                """, "demo.Service", """
                package demo;
                import dev.resilience4cata.annotation.Timeout;
                public class Service {
                    @Timeout("1s")
                    public Holder<String>.Inner find(Holder<Integer>.Inner key) {
                        return null;
                    }
                }
                """));

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.generatedSource("demo.Client$Resilient"))
                .contains("public demo.Holder<java.lang.String>.Inner find(demo.Holder<java.lang.Integer>.Inner p0)");
        assertThat(result.generatedSource("demo.Service$Resilient"))
                .contains("public demo.Holder<java.lang.String>.Inner find(demo.Holder<java.lang.Integer>.Inner key)");
    }

    @Test
    void passesARecursivelyBoundedTypeVariableExplicitly() {
        // javac could not infer T in () -> this.delegate.max() for <T extends Comparable<T>>, nor from a
        // Collection<? super T> argument in the call to the next private layer
        Compilation result = Compilation.compile(workDir, Map.of("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.CircuitBreaker;
                import dev.resilience4cata.annotation.Timeout;
                import java.util.concurrent.CompletionStage;
                public interface Client {
                    @CircuitBreaker @Timeout("1s")
                    <T extends Number & Comparable<T>> CompletionStage<T> max();
                }
                """, "demo.Service", """
                package demo;
                import dev.resilience4cata.annotation.RateLimiter;
                import dev.resilience4cata.annotation.Timeout;
                import java.util.Collection;
                public class Service {
                    @Timeout("1s") @RateLimiter
                    public <T extends Comparable<? super T>> int add(Collection<? super T> items) {
                        return 0;
                    }
                }
                """));

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.generatedSource("demo.Client$Resilient"))
                .contains("this.timeout0.executeAsync(() -> this.delegate.<T>max())");
        assertThat(result.generatedSource("demo.Service$Resilient"))
                .contains("return this.<T>r4c$timeout0(items);")
                .contains("r4c$result = super.<T>add(items);");
    }

    @Test
    void aHandlerOnAMethodThatThrowsATypeVariableCompiles() {
        // the handler's exceptions were rethrown with "instanceof E", which does not compile
        Compilation result = Compilation.compile(workDir, Map.of("demo.Client", """
                package demo;
                import dev.resilience4cata.FallbackContext;
                import dev.resilience4cata.FallbackHandler;
                import dev.resilience4cata.annotation.Fallback;
                public interface Client {
                    @Fallback(Client.Empty.class)
                    <E extends Exception> String run() throws E, java.io.IOException;

                    final class Empty implements FallbackHandler<String> {
                        @Override
                        public String apply(FallbackContext context) {
                            return "";
                        }
                    }
                }
                """));

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.generatedSource("demo.Client$Resilient"))
                .contains("if (r4c$x instanceof java.io.IOException r4c$d0) {")
                .doesNotContain("instanceof E ");
    }

    @Test
    void aClassUsesTheStaticFallbackOfAnInterfaceItImplements() {
        // static interface methods are not members of the class, so the class's subclass found no fallback
        Compilation result = Compilation.compile(workDir, Map.of("demo.Contract", """
                package demo;
                import dev.resilience4cata.annotation.Fallback;
                public interface Contract {
                    @Fallback(method = "cached")
                    String find(String id);

                    static String cached(String id, Exception e) {
                        return "cached";
                    }
                }
                """, "demo.Store", """
                package demo;
                import dev.resilience4cata.annotation.Timeout;
                public class Store implements Contract {
                    @Override
                    @Timeout("1s")
                    public String find(String id) {
                        return id;
                    }
                }
                """, "demo.Sub", """
                package demo;
                public interface Sub extends Contract {
                    @dev.resilience4cata.annotation.Timeout("1s")
                    void ping();
                }
                """));

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.generatedSource("demo.Store$Resilient")).contains("return demo.Contract.cached(id, r4c$f0);");
        assertThat(result.generatedSource("demo.Sub$Resilient")).contains("return demo.Contract.cached(p0, r4c$f0);");
    }

    @Test
    void conflictingBundlesAreReportedOnEveryMethodWithTheSameName() {
        // the report was deduplicated by the method's toString(), so the second bad() got no error
        String bundles = """
                package demo;
                import dev.resilience4cata.annotation.ResiliencePolicy;
                import dev.resilience4cata.annotation.Timeout;
                public final class Bundles {
                    private Bundles() {
                    }
                    @ResiliencePolicy @Timeout("1s")
                    public @interface Fast {
                    }
                    @ResiliencePolicy @Timeout("2s")
                    public @interface Slow {
                    }
                }
                """;
        Compilation result = Compilation.compile(workDir, Map.of("demo.Bundles", bundles, "demo.First", """
                package demo;
                public interface First {
                    @Bundles.Fast @Bundles.Slow
                    String bad();
                }
                """, "demo.Second", """
                package demo;
                public interface Second {
                    @Bundles.Fast @Bundles.Slow
                    String bad();
                }
                """));

        assertThat(result.errorCodes()).containsExactly("R4C-CMP-002", "R4C-CMP-002");
    }

    @Test
    void aMethodMayChangeTheWindowTypeThatItsTypeImplies() {
        Compilation result = Compilation.compile(workDir, Map.of("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.CircuitBreaker;
                import dev.resilience4cata.annotation.WindowType;
                @CircuitBreaker(windowSize = 50)
                public interface Client {
                    @CircuitBreaker(windowType = WindowType.TIME, windowDuration = "10s")
                    String find(String id);
                }
                """));

        assertThat(result.diagnostics()).isEmpty();
    }
}
