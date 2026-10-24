package dev.resilience4cata.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code @Fallback} on generic methods: sibling methods with the same type parameters, and handlers. */
class GenericFallbackProcessorTest {

    @TempDir
    Path workDir;

    private Compilation compile(String qualifiedName, String source) {
        return Compilation.compile(workDir, Map.of(qualifiedName, source));
    }

    @Test
    void callsSiblingFallbacksWithExplicitTypeArguments() {
        Compilation result = compile("demo.Repo", """
                package demo;
                import dev.resilience4cata.annotation.Fallback;
                import dev.resilience4cata.annotation.Retry;
                import dev.resilience4cata.annotation.Timeout;
                import java.util.List;
                public interface Repo<E> {
                    @Retry @Fallback(method = "load")
                    <T> T load(Class<T> type, String id) throws java.io.IOException;
                    default <U> U load(Class<U> type, String id, java.io.IOException e) { return null; }
                    default <U> U load(Class<U> type, String id, Exception e) { return null; }

                    @Fallback(method = "all")
                    <T extends Comparable<T>> List<T> all(List<T> in);
                    static <V extends Comparable<V>> List<V> all(List<V> in, Throwable e) { return in; }

                    @Fallback(method = "first") @Timeout("1s")
                    <T extends Number & Comparable<T>, K> T max(T a, T b, K key);
                    default <A extends Number & Comparable<A>, B> A first(A a, A b, B key) { return a; }

                    @Fallback(method = "narrow")
                    <T extends E> T narrow(T value);
                    default <T extends E> T narrow(T value, RuntimeException e) { return value; }

                    @Fallback(method = "store")
                    <T> void store(T value);
                    default <T> void store(T value, Exception e) { }
                }
                """);

        assertThat(result.errors()).isEmpty();
        assertThat(result.generatedSource("demo.Repo$Resilient"))
                .contains("public <T> T load(java.lang.Class<T> p0, java.lang.String p1) throws java.io.IOException {")
                .contains("return this.delegate.<T>load(p0, p1, r4c$f0);")
                .contains("return this.delegate.<T>load(p0, p1, r4c$f1);")
                .contains("return demo.Repo.<T>all(p0, r4c$e);")
                .contains("public <T extends java.lang.Number & java.lang.Comparable<T>, K> T max(T p0, T p1, K p2) {")
                .contains("return this.delegate.<T, K>first(p0, p1, p2);")
                .contains("private <T extends java.lang.Number & java.lang.Comparable<T>, K> T r4c$timeout4(T p0, T p1, K p2)")
                .contains("return this.delegate.<T>narrow(p0, r4c$f0);")
                .contains("this.delegate.<T>store(p0, r4c$f0);");
    }

    @Test
    void generatesGenericAsyncFallbacks() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.Fallback;
                import dev.resilience4cata.annotation.Retry;
                import java.util.List;
                import java.util.concurrent.CompletableFuture;
                import java.util.concurrent.CompletionStage;
                public interface Client {
                    @Retry @Fallback(method = "fetch")
                    <T> CompletionStage<T> fetch(Class<T> type);
                    default <T> T fetch(Class<T> type, Throwable e) { return null; }

                    @Fallback(method = "list")
                    <T> CompletableFuture<List<T>> list();
                    default <T> CompletionStage<List<T>> list(Exception e) { return CompletableFuture.completedFuture(List.of()); }
                }
                """);

        assertThat(result.errors()).isEmpty();
        assertThat(result.generatedSource("demo.Client$Resilient"))
                .contains("(r4c$e, r4c$attempts) -> this.<T>r4c$recover0(r4c$e, r4c$attempts, p0)")
                .contains("private <T> java.util.concurrent.CompletionStage<T> r4c$recover0(")
                .contains("return java.util.concurrent.CompletableFuture.completedFuture(this.delegate.<T>fetch(p0, r4c$e));")
                .contains("(r4c$e, r4c$attempts) -> this.<T>r4c$recover2(r4c$e, r4c$attempts)")
                .contains("return this.delegate.<T>list(r4c$f0);");
    }

    @Test
    void acceptsAHandlerWhenTheResultDoesNotDependOnTheTypeParameters() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.FallbackContext;
                import dev.resilience4cata.annotation.Fallback;
                import dev.resilience4cata.annotation.Retry;
                public interface Client {
                    @Retry @Fallback(Text.class)
                    <T> String describe(T value);

                    final class Text implements dev.resilience4cata.FallbackHandler<String> {
                        @Override
                        public String apply(FallbackContext context) {
                            return "?";
                        }
                    }
                }
                """);

        assertThat(result.errors()).isEmpty();
        assertThat(result.generatedSource("demo.Client$Resilient"))
                .contains("public <T> java.lang.String describe(T p0) {")
                .contains("return this.fallbackHandler0.apply(r4c$context);");
    }

    @Test
    void rejectsFallbacksThatDoNotMatchTheTypeParameters() {
        Compilation result = compile("demo.Client", """
                package demo;
                import dev.resilience4cata.FallbackContext;
                import dev.resilience4cata.annotation.Fallback;
                import java.util.List;
                import java.util.concurrent.CompletionStage;
                public interface Client {
                    @Fallback(method = "count")
                    <T> T count(Class<T> type);
                    default Object count(Class<?> type, Exception e) { return null; }

                    @Fallback(method = "bound")
                    <T extends Number> T bound(T value);
                    default <T> T bound(T value, Exception e) { return value; }

                    @Fallback(method = "wide")
                    <T> T wide(Class<T> type);
                    default <T> Object wide(Class<T> type, Exception e) { return null; }

                    @Fallback(method = "variable")
                    <X extends Exception> String variable(X failure);
                    default <X extends Exception> String variable(X failure, X e) { return ""; }

                    @Fallback(method = "plain")
                    String plain(String id);
                    default <T> String plain(String id, Exception e) { return ""; }

                    @Fallback(Any.class)
                    <T> List<T> handled();
                    @Fallback(Any.class)
                    <T> CompletionStage<T> handledAsync();

                    final class Any implements dev.resilience4cata.FallbackHandler<Object> {
                        @Override
                        public Object apply(FallbackContext context) {
                            return "";
                        }
                    }
                }
                """);

        assertThat(result.errors()).hasSize(7)
                .anySatisfy(e -> assertThat(e).contains("no usable method count")
                        .contains("Expected a default or static method <T> T count(java.lang.Class<T>, <a Throwable type> e)")
                        .contains("count(java.lang.Class<?>, java.lang.Exception) does not declare the same type parameters"
                                + " as count: <T>."))
                .anySatisfy(e -> assertThat(e).contains("no usable method bound")
                        .contains("<T> bound(T, java.lang.Exception) does not declare the same type parameters as bound:"
                                + " <T extends java.lang.Number>."))
                .anySatisfy(e -> assertThat(e).contains("no usable method wide")
                        .contains("<T> wide(java.lang.Class<T>, java.lang.Exception) returns java.lang.Object, which is not"
                                + " assignable to T."))
                .anySatisfy(e -> assertThat(e).contains("no usable method variable")
                        .contains("<X extends java.lang.Exception> variable(X, X) takes a X as its exception parameter, which is not a class"))
                .anySatisfy(e -> assertThat(e).contains("no usable method plain")
                        .contains("<T> plain(java.lang.String, java.lang.Exception) does not declare the same type"
                                + " parameters as plain: none."))
                .anySatisfy(e -> assertThat(e).contains("@Fallback(demo.Client.Any.class): Any implements"
                        + " FallbackHandler<java.lang.Object>, but the generic method handled returns java.util.List<T>,"
                        + " which depends on its type parameters").contains("@Fallback(method = \"handled\")"))
                .anySatisfy(e -> assertThat(e).contains("the generic method handledAsync returns"
                        + " java.util.concurrent.CompletionStage<T>, which depends on its type parameters"));
    }
}
