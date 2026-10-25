package dev.resilience4cata.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The subclass style: annotated non-final classes get a generated {@code <Class>$Resilient extends Class}. */
class SubclassProcessorTest {

    @TempDir
    Path workDir;

    private Compilation compile(String qualifiedName, String source) {
        return Compilation.compile(workDir, Map.of(qualifiedName, source));
    }

    @Test
    void generatesASubclassThatCompilesWithoutWarnings() {
        Compilation result = compile("demo.Service", """
                package demo;
                import dev.resilience4cata.annotation.*;
                import java.util.concurrent.CompletableFuture;
                public class Service implements java.io.Serializable {
                    private static final long serialVersionUID = 1L;
                    private final String prefix;
                    public Service(String prefix, int... sizes) throws java.io.IOException { this.prefix = prefix; }
                    protected Service() { this.prefix = ""; }
                    private Service(long ignored) { this.prefix = ""; }
                    @Retry(maxAttempts = 5) @CircuitBreaker @Timeout("1s") @Bulkhead @RateLimiter @Fallback(method = "recover")
                    public String call(String argument) throws java.io.IOException { return prefix + argument; }
                    String recover(String argument, Exception e) { return "recovered"; }
                    @Retry @Fallback(method = "later")
                    protected CompletableFuture<String> async(int id) { return CompletableFuture.completedFuture("x"); }
                    String later(int id) { return "later"; }
                    @Timeout("1s")
                    <T extends Comparable<T>> T generic(T value) { return value; }
                    public String plain() { return "plain"; }
                }
                """);

        assertThat(result.diagnostics()).map(d -> d.getMessage(java.util.Locale.ROOT)).isEmpty();
        assertThat(result.success()).isTrue();
        String generated = result.generatedSource("demo.Service$Resilient");
        assertThat(generated)
                .contains("public final class Service$Resilient extends demo.Service {")
                .contains("public Service$Resilient(dev.resilience4cata.ResilienceRegistry registry, java.lang.String p0,"
                        + " int... p1) throws java.io.IOException {\n        super(p0, p1);\n")
                .contains("public Service$Resilient(dev.resilience4cata.ResilienceRegistry registry) {\n        super();\n")
                .doesNotContain("long p0")
                .contains("\"demo.Service#call(java.lang.String)\"")
                .contains("return super.call(p0);")
                .contains("return this.recover(p0, r4c$f0);")
                .contains("public java.util.concurrent.CompletableFuture<java.lang.String> async(int p0)")
                .contains("super.async(p0)")
                .contains("this.later(p0)")
                .contains("public <T extends java.lang.Comparable<T>> T generic(T p0)")
                .doesNotContain("plain")
                .doesNotContain("delegate");
        // every constructor sets up every policy
        assertThat(generated.split("this\\.retry0 = registry\\.retryPolicy", -1)).hasSize(3);
    }

    @Test
    void classAnnotationsWinOverInterfaceAnnotationsAttributeByAttribute() {
        Compilation result = Compilation.compile(workDir, Map.of(
                "demo.Api", """
                        package demo;
                        import dev.resilience4cata.annotation.Retry;
                        @Retry(delay = "1s", maxAttempts = 2)
                        public interface Api {
                            @Retry(maxAttempts = 3, multiplier = 2.0)
                            String call();
                            String other();
                            String unannotated();
                        }
                        """,
                "demo.Base", """
                        package demo;
                        import dev.resilience4cata.annotation.Retry;
                        @Retry(maxDelay = "9s")
                        public class Base {
                            @Retry(jitter = dev.resilience4cata.annotation.Jitter.FULL)
                            public String inherited() { return ""; }
                            public String other() { return ""; }
                            @Retry(maxAttempts = 5, multiplier = 3.0) public String overridden() { return ""; }
                        }
                        """,
                "demo.Impl", """
                        package demo;
                        import dev.resilience4cata.annotation.Retry;
                        @Retry(maxDuration = "30s")
                        public class Impl extends Base implements Api {
                            @Retry(maxAttempts = 4)
                            public String call() { return ""; }
                            public String unannotated() { return ""; }
                            @Override @Retry(maxAttempts = 6) public String overridden() { return ""; }
                            @Override public String toString() { return "impl"; }
                            @Override public int hashCode() { return 1; }
                            @Override @Retry(maxAttempts = 7) public boolean equals(Object o) { return o == this; }
                        }
                        """));

        assertThat(result.errors()).isEmpty();
        String generated = result.generatedSource("demo.Impl$Resilient");
        // call: interface type < interface method < class type < class method
        assertThat(section(generated, "demo.Impl#call()"))
                .contains("r4c$b.delay(java.time.Duration.ofNanos(1000000000L));")
                .contains("r4c$b.maxAttempts(4);")
                .contains("r4c$b.multiplier(2.0);")
                .contains("r4c$b.maxDuration(java.time.Duration.ofNanos(30000000000L));")
                .doesNotContain("maxAttempts(3)")
                .doesNotContain("maxAttempts(2)")
                .doesNotContain("maxDelay");
        // other: declared by the superclass (type level) and the interface (type level), then the class
        assertThat(section(generated, "demo.Impl#other()"))
                .contains("r4c$b.maxAttempts(2);")
                .contains("r4c$b.maxDelay(java.time.Duration.ofNanos(9000000000L));")
                .contains("r4c$b.maxDuration(java.time.Duration.ofNanos(30000000000L));");
        // inherited: the superclass method's own annotation applies; the interface's type level does not
        assertThat(section(generated, "demo.Impl#inherited()"))
                .contains("r4c$b.jitter(dev.resilience4cata.RetryConfig.Jitter.FULL);")
                .contains("r4c$b.maxDelay(java.time.Duration.ofNanos(9000000000L));")
                .doesNotContain("maxAttempts");
        // an overriding method wins over the superclass method it overrides
        assertThat(section(generated, "demo.Impl#overridden()"))
                .contains("r4c$b.maxAttempts(6);")
                .contains("r4c$b.multiplier(3.0);")
                .doesNotContain("maxAttempts(5)");
        // the class's type level applies to its own methods; Object methods only take method annotations
        assertThat(generated).contains("\"demo.Impl#unannotated()\"")
                .doesNotContain("toString").doesNotContain("hashCode")
                .contains("public boolean equals(java.lang.Object p0)");
        assertThat(section(generated, "demo.Impl#equals(java.lang.Object)"))
                .contains("r4c$b.maxAttempts(7);")
                .doesNotContain("maxDuration");
    }

    @Test
    void interfaceAnnotationsAloneDoNotMakeAClassGenerateASubclass() {
        Compilation result = Compilation.compile(workDir, Map.of(
                "demo.Api", """
                        package demo;
                        public interface Api { @dev.resilience4cata.annotation.Retry String call(); }
                        """,
                "demo.Impl", "package demo; public final class Impl implements Api { public String call() { return \"\"; } }",
                "demo.Plain", "package demo; public class Plain { public String call() { return \"\"; } }"));

        assertThat(result.errors()).isEmpty();
        assertThat(Files.exists(result.generated().resolve("demo/Api$Resilient.java"))).isTrue();
        assertThat(Files.exists(result.generated().resolve("demo/Impl$Resilient.java"))).isFalse();
        assertThat(Files.exists(result.generated().resolve("demo/Plain$Resilient.java"))).isFalse();
    }

    @Test
    void generatesSubclassesForGenericAndNestedClasses() {
        Compilation result = Compilation.compile(workDir, Map.of(
                "demo.Store", """
                        package demo;
                        import dev.resilience4cata.annotation.Retry;
                        public class Store<K extends Comparable<K>, V> {
                            @Retry
                            public V get(K key) { return null; }
                            public static class Cache extends Store<String, Integer> {
                                @Retry public Integer size() { return 0; }
                            }
                        }
                        """,
                "demo.Holder", """
                        package demo;
                        public interface Holder {
                            class Nested { @dev.resilience4cata.annotation.Timeout("1s") public void run() {} }
                        }
                        """));

        assertThat(result.errors()).isEmpty();
        assertThat(result.generatedSource("demo.Store$Resilient"))
                .contains("public final class Store$Resilient<K extends java.lang.Comparable<K>, V> extends demo.Store<K, V>")
                .contains("public V get(K p0)");
        assertThat(result.generatedSource("demo.Store$Cache$Resilient"))
                .contains("extends demo.Store.Cache {")
                .contains("public java.lang.Integer get(java.lang.String p0)")
                .contains("public java.lang.Integer size()");
        assertThat(result.generatedSource("demo.Holder$Nested$Resilient")).contains("super.run();");
    }

    @Test
    void rejectsClassesThatCannotBeExtended() {
        Compilation result = Compilation.compile(workDir, Map.of(
                "demo.Final", """
                        package demo;
                        public final class Final { @dev.resilience4cata.annotation.Retry public void call() {} }
                        """,
                "demo.Abstract", """
                        package demo;
                        public abstract class Abstract { @dev.resilience4cata.annotation.Retry public void call() {} }
                        """,
                "demo.Sealed", """
                        package demo;
                        public sealed class Sealed permits Sealed.Sub {
                            @dev.resilience4cata.annotation.Retry public void call() {}
                            public static final class Sub extends Sealed {}
                        }
                        """,
                "demo.Outer", """
                        package demo;
                        public class Outer {
                            public class Inner { @dev.resilience4cata.annotation.Retry public void call() {} }
                            private static class Hidden { @dev.resilience4cata.annotation.Retry public void call() {} }
                        }
                        """,
                "demo.NoConstructor", """
                        package demo;
                        public class NoConstructor {
                            private NoConstructor() {}
                            @dev.resilience4cata.annotation.Retry public void call() {}
                        }
                        """,
                "demo.Kinds", """
                        package demo;
                        import dev.resilience4cata.annotation.Retry;
                        public interface Kinds {
                            record Point(int x) { @Retry public int x() { return x; } }
                            enum Color { RED; @Retry public void paint() {} }
                        }
                        """));

        assertThat(result.errorCodes()).containsExactlyInAnyOrder(
                "R4C-SUB-005", "R4C-SUB-004", "R4C-SUB-003", "R4C-SUB-002", "R4C-SUB-001", "R4C-APT-001", "R4C-APT-001", "R4C-SUB-006");
        assertThat(result.errors())
                .anySatisfy(e -> assertThat(e).contains("Final is final, so the generated subclass cannot extend it"))
                .anySatisfy(e -> assertThat(e).contains("Abstract is abstract"))
                .anySatisfy(e -> assertThat(e).contains("Sealed is sealed"))
                .anySatisfy(e -> assertThat(e).contains("Inner is an inner class"))
                .anySatisfy(e -> assertThat(e).contains("Hidden is private"))
                .anySatisfy(e -> assertThat(e).contains("NoConstructor has only private constructors"))
                .anySatisfy(e -> assertThat(e).contains("cannot be used on records"))
                .anySatisfy(e -> assertThat(e).contains("cannot be used on enums"))
                .hasSize(8);
    }

    @Test
    void rejectsMethodsTheSubclassCannotOverride() {
        Compilation result = Compilation.compile(workDir, Map.of(
                "demo.Api", "package demo; public interface Api { @dev.resilience4cata.annotation.Retry String api(); }",
                "other.Base", """
                        package other;
                        public class Base {
                            @dev.resilience4cata.annotation.Retry void hidden() {}
                        }
                        """,
                "demo.Service", """
                        package demo;
                        import dev.resilience4cata.annotation.Retry;
                        public class Service extends other.Base implements Api {
                            @Retry public final void sealedMethod() {}
                            @Retry private void secret() {}
                            @Retry public static void shared() {}
                            public final String api() { return ""; }
                            @Retry public void fine() {}
                        }
                        """));

        assertThat(result.errorCodes()).containsExactlyInAnyOrder(
                "R4C-SUB-007", "R4C-SUB-007", "R4C-SUB-008", "R4C-APT-002", "R4C-APT-002");
        assertThat(result.errors())
                .anySatisfy(e -> assertThat(e).contains("sealedMethod() is final, so the generated subclass cannot override it"))
                .anySatisfy(e -> assertThat(e).contains("api() is final"))
                .anySatisfy(e -> assertThat(e).contains("hidden() (inherited from other.Base) is package-private in another package"))
                .anySatisfy(e -> assertThat(e).contains("static or private methods"))
                .hasSize(5);
    }

    @Test
    void typeLevelAnnotationsSkipFinalMethods() {
        Compilation result = compile("demo.Service", """
                package demo;
                @dev.resilience4cata.annotation.Retry
                public class Service {
                    public final String fixed() { return ""; }
                    public String call() { return ""; }
                }
                """);

        assertThat(result.errors()).isEmpty();
        assertThat(result.generatedSource("demo.Service$Resilient")).contains("call()").doesNotContain("fixed");
    }

    @Test
    void rejectsPrivateFallbackMethods() {
        Compilation result = compile("demo.Service", """
                package demo;
                import dev.resilience4cata.annotation.Fallback;
                public class Service {
                    @Fallback(method = "recover")
                    public String call() { return ""; }
                    private String recover(Exception e) { return ""; }
                }
                """);

        assertThat(result.errorCodes()).containsExactlyInAnyOrder("R4C-FB-003");
        assertThat(result.errors()).singleElement().asString()
                .contains("no usable method recover in Service. Expected a non-private method")
                .contains("recover(java.lang.Exception) is private, so the generated class cannot call it. Make it"
                        + " package-private, protected or public.");
    }

    @Test
    void acceptsStaticAndInheritedFallbackMethods() {
        Compilation result = Compilation.compile(workDir, Map.of(
                "demo.Base", """
                        package demo;
                        public class Base {
                            protected String recover(String id, RuntimeException e) { return "base"; }
                        }
                        """,
                "demo.Service", """
                        package demo;
                        import dev.resilience4cata.annotation.Fallback;
                        public class Service extends Base {
                            @Fallback(method = "recover")
                            public String call(String id) { return id; }
                            @Fallback(method = "fixed")
                            public String other(String id) { return id; }
                            static String fixed(String id) { return "fixed"; }
                        }
                        """));

        assertThat(result.errors()).isEmpty();
        assertThat(result.generatedSource("demo.Service$Resilient"))
                .contains("return this.recover(p0, r4c$f0);")
                .contains("return demo.Service.fixed(p0);");
    }

    /** The part of the generated source that sets up the policy with this instance id. */
    private static String section(String generated, String instanceId) {
        int start = generated.indexOf("\"" + instanceId + "\"");
        assertThat(start).as(instanceId).isNotNegative();
        return generated.substring(start, generated.indexOf("});", start));
    }
}
