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
                .contains("public class Service$Resilient extends demo.Service {")
                .contains("""
                        public Service$Resilient(dev.resilience4cata.ResilienceRegistry registry, java.lang.String prefix, \
                        int... sizes) throws java.io.IOException {
                                super(prefix, sizes);
                        """)
                .contains("public Service$Resilient(dev.resilience4cata.ResilienceRegistry registry) {\n        super();\n")
                .doesNotContain("long ignored")
                .contains("\"demo.Service#call(java.lang.String)\"")
                .contains("return super.call(argument);")
                .contains("return this.recover(argument, r4c$f0);")
                .contains("public java.util.concurrent.CompletableFuture<java.lang.String> async(int id)")
                .contains("super.async(id)")
                .contains("this.later(id)")
                .contains("public <T extends java.lang.Comparable<T>> T generic(T value)")
                .doesNotContain("plain")
                .doesNotContain("delegate");
        // every constructor sets up every policy
        assertThat(generated.split("this\\.retry0 = dev\\.resilience4cata\\.generated\\.Policies\\.retry\\(registry", -1)).hasSize(3);
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
                .contains("public boolean equals(java.lang.Object o)");
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
                .contains("public class Store$Resilient<K extends java.lang.Comparable<K>, V> extends demo.Store<K, V>")
                .contains("public V get(K key)");
        assertThat(result.generatedSource("demo.Store$Cache$Resilient"))
                .contains("extends demo.Store.Cache {")
                .contains("public java.lang.Integer get(java.lang.String key)")
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
                .contains("return this.recover(id, r4c$f0);")
                .contains("return demo.Service.fixed(id);");
    }

    @Test
    void mirrorsConstructorAnnotationsParameterAnnotationsAndNames() throws Exception {
        Compilation result = Compilation.compile(workDir, Map.of(
                "demo.Inject", """
                        package demo;
                        import java.lang.annotation.*;
                        @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.CONSTRUCTOR, ElementType.METHOD})
                        public @interface Inject {}
                        """,
                "demo.Qualifier", """
                        package demo;
                        import java.lang.annotation.*;
                        @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.PARAMETER)
                        public @interface Qualifier {
                            String value();
                            Mode mode() default Mode.STRICT;
                            Class<?>[] types() default {};
                            Tag[] tags() default {};
                            long size() default 0;
                            char separator() default ',';
                            enum Mode { STRICT, LENIENT }
                        }
                        """,
                "demo.Tag", """
                        package demo;
                        import java.lang.annotation.*;
                        @Retention(RetentionPolicy.CLASS) @Target({})
                        public @interface Tag { String value(); double weight() default 1; }
                        """,
                "demo.Marker", """
                        package demo;
                        import java.lang.annotation.*;
                        @Retention(RetentionPolicy.SOURCE)
                        public @interface Marker {}
                        """,
                "demo.Both", """
                        package demo;
                        import java.lang.annotation.*;
                        @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.PARAMETER, ElementType.TYPE_USE})
                        public @interface Both {}
                        """,
                "demo.Service", """
                        package demo;
                        import dev.resilience4cata.annotation.Retry;
                        import org.jspecify.annotations.Nullable;
                        public class Service {
                            public Service() {}
                            @Inject @Marker @Deprecated
                            public Service(@Qualifier(value = "primary\\\\db \\"x\\" \\u00e9", mode = Qualifier.Mode.LENIENT,
                                    types = {String.class, int[].class, java.util.Map.Entry.class},
                                    tags = {@Tag("a"), @Tag(value = "b", weight = 0.5)}, size = 3, separator = '\\\\')
                                    String dataSource, @Marker @Both @Nullable String registry, int java, long r4c$x) {}
                            @java.beans.ConstructorProperties({"name"})
                            Service(String name) {}
                            @SafeVarargs
                            protected Service(java.util.List<String>... lists) {}
                            @Retry public String call() { return ""; }
                        }
                        """));

        assertThat(result.diagnostics()).map(d -> d.getMessage(java.util.Locale.ROOT)).isEmpty();
        String generated = result.generatedSource("demo.Service$Resilient");
        assertThat(generated)
                // without annotations or parameters, the constructor is written as before
                .contains("\n    public Service$Resilient(dev.resilience4cata.ResilienceRegistry registry) {\n        super();\n")
                .contains("""
                            @demo.Inject
                            @java.lang.Deprecated
                            public Service$Resilient(dev.resilience4cata.ResilienceRegistry r4c$registry, \
                        @demo.Qualifier(value = "primary\\\\db \\"x\\" \\u00e9", mode = demo.Qualifier.Mode.LENIENT, \
                        types = {java.lang.String.class, int[].class, java.util.Map.Entry.class}, \
                        tags = {@demo.Tag("a"), @demo.Tag(value = "b", weight = 0.5)}, size = 3L, separator = '\\\\') \
                        java.lang.String dataSource, @demo.Both java.lang.String registry, int r4c$p2, long r4c$p3) {
                                super(dataSource, registry, r4c$p2, r4c$p3);
                                java.util.Objects.requireNonNull(r4c$registry, "registry");
                                dev.resilience4cata.generated.Abi.require(2, Service$Resilient.class);
                                this.retry0 = dev.resilience4cata.generated.Policies.retry(r4c$registry,""")
                .contains("""
                            @java.beans.ConstructorProperties({"registry", "name"})
                            public Service$Resilient(dev.resilience4cata.ResilienceRegistry registry, java.lang.String name) {
                        """)
                .contains("""
                            @java.lang.SafeVarargs
                            public Service$Resilient(dev.resilience4cata.ResilienceRegistry registry, \
                        java.util.List<java.lang.String>... lists) {
                        """)
                .doesNotContain("Marker")
                .doesNotContain("Nullable");

        // what a dependency injection framework reads at run time
        try (java.net.URLClassLoader loader = new java.net.URLClassLoader(new java.net.URL[] {result.classes().toUri().toURL()},
                getClass().getClassLoader())) {
            Class<?> type = loader.loadClass("demo.Service$Resilient");
            java.lang.reflect.Constructor<?> injected = type.getConstructor(dev.resilience4cata.ResilienceRegistry.class,
                    String.class, String.class, int.class, long.class);
            assertThat(injected.getAnnotations()).extracting(a -> a.annotationType().getSimpleName())
                    .containsExactly("Inject", "Deprecated");
            java.lang.annotation.Annotation[][] parameters = injected.getParameterAnnotations();
            assertThat(parameters[0]).isEmpty();
            java.lang.annotation.Annotation qualifier = parameters[1][0];
            assertThat(qualifier.annotationType().getMethod("value").invoke(qualifier)).isEqualTo("primary\\db \"x\" \u00e9");
            assertThat(qualifier.annotationType().getMethod("separator").invoke(qualifier)).isEqualTo('\\');
            assertThat(qualifier.toString()).contains("mode=LENIENT").contains("size=3L");
            assertThat(parameters[2]).extracting(a -> a.annotationType().getSimpleName()).containsExactly("Both");
            java.beans.ConstructorProperties properties = type.getConstructor(dev.resilience4cata.ResilienceRegistry.class,
                    String.class).getAnnotation(java.beans.ConstructorProperties.class);
            assertThat(properties.value()).containsExactly("registry", "name");
        }
    }

    @Test
    void repeatsTheAnnotationsOfOverriddenMethodsAndTheirParameters() throws Exception {
        Compilation result = Compilation.compile(workDir, Map.of(
                "demo.Transactional", """
                        package demo;
                        import java.lang.annotation.*;
                        @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.METHOD, ElementType.TYPE})
                        public @interface Transactional { String value() default ""; boolean readOnly() default false; }
                        """,
                "demo.Valid", """
                        package demo;
                        import java.lang.annotation.*;
                        @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.PARAMETER)
                        public @interface Valid {}
                        """,
                "demo.Marker", """
                        package demo;
                        import java.lang.annotation.*;
                        @Retention(RetentionPolicy.SOURCE) @Target(ElementType.METHOD)
                        public @interface Marker {}
                        """,
                "demo.Standard", """
                        package demo;
                        import java.lang.annotation.*;
                        @dev.resilience4cata.annotation.ResiliencePolicy
                        @dev.resilience4cata.annotation.Retry(maxAttempts = 2)
                        @Retention(RetentionPolicy.CLASS) @Target({ElementType.TYPE, ElementType.METHOD})
                        public @interface Standard {}
                        """,
                "demo.Service", """
                        package demo;
                        import dev.resilience4cata.annotation.*;
                        import java.util.concurrent.CompletableFuture;
                        @Transactional("type level")
                        public class Service {
                            @Retry(maxAttempts = 3) @CircuitBreaker @Fallback(method = "recover") @Transactional(value = "tx", readOnly = true)
                            @Marker @Deprecated
                            public String call(@Valid String id, int count) { return id; }
                            String recover(String id, int count, Exception e) { return "recovered"; }
                            @Standard @Transactional
                            public CompletableFuture<String> async(@Valid String id) { return CompletableFuture.completedFuture(id); }
                            @Timeout("1s")
                            public void plain(String value) {}
                        }
                        """));

        assertThat(result.errors()).isEmpty();
        String generated = result.generatedSource("demo.Service$Resilient");
        assertThat(generated)
                .contains("""
                            @Override
                            @demo.Transactional(value = "tx", readOnly = true)
                            @java.lang.Deprecated
                            public java.lang.String call(@demo.Valid java.lang.String id, int count) {
                        """)
                .contains("""
                            @Override
                            @demo.Transactional
                            public java.util.concurrent.CompletableFuture<java.lang.String> async(@demo.Valid java.lang.String id) {
                        """)
                .contains("""
                            @Override
                            public void plain(java.lang.String value) {
                        """)
                // the inner layers are private methods: they carry no annotations
                .contains("    private java.lang.String r4c$circuitBreaker0(java.lang.String id, int count) {\n")
                .doesNotContain("Marker")
                .doesNotContain("@dev.resilience4cata.annotation")
                .doesNotContain("@demo.Standard")
                .doesNotContain("type level");
        // the generated class carries no policy annotation, so the processor does not process it again
        assertThat(Files.exists(result.generated().resolve("demo/Service$Resilient$Resilient.java"))).isFalse();

        try (java.net.URLClassLoader loader = new java.net.URLClassLoader(new java.net.URL[] {result.classes().toUri().toURL()},
                getClass().getClassLoader())) {
            Class<?> type = loader.loadClass("demo.Service$Resilient");
            java.lang.reflect.Method call = type.getDeclaredMethod("call", String.class, int.class);
            assertThat(call.getAnnotations()).extracting(a -> a.annotationType().getSimpleName())
                    .containsExactly("Transactional", "Deprecated");
            assertThat(call.getAnnotations()[0].toString()).contains("readOnly=true").contains("\"tx\"");
            assertThat(call.getParameterAnnotations()[0]).extracting(a -> a.annotationType().getSimpleName())
                    .containsExactly("Valid");
            assertThat(call.getParameterAnnotations()[1]).isEmpty();
        }
    }

    @Test
    void generatedSubclassesAreNotFinalSoFrameworksCanProxyThem() {
        Compilation result = Compilation.compile(workDir, Map.of(
                "demo.Service", """
                        package demo;
                        public class Service { @dev.resilience4cata.annotation.Retry public String call() { return ""; } }
                        """,
                "demo.Proxy", """
                        package demo;
                        public class Proxy extends Service$Resilient {
                            public Proxy() { super(dev.resilience4cata.ResilienceRegistry.builder().build()); }
                        }
                        """));

        assertThat(result.errors()).isEmpty();
        assertThat(result.generatedSource("demo.Service$Resilient")).contains("public class Service$Resilient extends demo.Service {");
    }

    @Test
    void writesNativeImageMetadataForEachGeneratedClass() {
        Compilation result = Compilation.compile(workDir, Map.of(
                "demo.Api", """
                        package demo;
                        public interface Api {
                            @dev.resilience4cata.annotation.Retry String call();
                            interface Nested { @dev.resilience4cata.annotation.Retry String call(); }
                        }
                        """,
                "demo.Service", """
                        package demo;
                        public class Service {
                            public Service(String first, Api.Nested... rest) {}
                            Service(int... sizes) {}
                            private Service(long... ignored) {}
                            @dev.resilience4cata.annotation.Retry public String call() { return ""; }
                        }
                        """));

        assertThat(result.errors()).isEmpty();
        assertThat(result.nativeImageMetadata("demo.Api$Resilient")).isEqualTo("""
                {
                  "reflection": [
                    {
                      "condition": {"typeReached": "demo.Api"},
                      "type": "demo.Api$Resilient",
                      "allPublicConstructors": true
                    }
                  ]
                }
                """);
        assertThat(result.nativeImageMetadata("demo.Api$Nested$Resilient"))
                .contains("\"condition\": {\"typeReached\": \"demo.Api$Nested\"}")
                .contains("\"type\": \"demo.Api$Nested$Resilient\"");
        // Resilient.create(Service.class, ...) can run before Service is initialized: the condition is Resilient
        assertThat(result.nativeImageMetadata("demo.Service$Resilient")).isEqualTo("""
                {
                  "reflection": [
                    {
                      "condition": {"typeReached": "dev.resilience4cata.Resilient"},
                      "type": "demo.Service$Resilient",
                      "allPublicConstructors": true
                    },
                    {
                      "condition": {"typeReached": "dev.resilience4cata.Resilient"},
                      "type": "demo.Api$Nested[]"
                    },
                    {
                      "condition": {"typeReached": "dev.resilience4cata.Resilient"},
                      "type": "int[]"
                    }
                  ]
                }
                """);
    }

    @Test
    void overridesKeepTheDeclaredParameterNamesExceptThoseThatWouldObscureAPackage() {
        Compilation result = compile("demo.Service", """
                package demo;
                import dev.resilience4cata.annotation.*;
                import java.util.concurrent.CompletableFuture;
                public class Service {
                    @Retry @Timeout("1s") @Fallback(method = "recover")
                    public String find(String sku, int warehouse, String java, int dev, long r4c$attempts) { return sku; }
                    String recover(String sku, int warehouse, String java, int dev, long r4c$attempts) { return "x"; }
                    @Retry @Timeout("1s") @Fallback(method = "later")
                    public CompletableFuture<String> async(String sku, String... tags) {
                        return CompletableFuture.completedFuture(sku);
                    }
                    CompletableFuture<String> later(String sku, String[] tags, Throwable failure) {
                        return CompletableFuture.completedFuture("later");
                    }
                    @Fallback(method = "fixed")
                    public String named(String demo) { return demo; }
                    static String fixed(String demo) { return "fixed"; }
                }
                """);

        assertThat(result.diagnostics()).map(d -> d.getMessage(java.util.Locale.ROOT)).isEmpty();
        String generated = result.generatedSource("demo.Service$Resilient");
        String declared = "(java.lang.String sku, int warehouse, java.lang.String r4c$p2, int r4c$p3, long r4c$p4)";
        assertThat(generated)
                // frameworks read these names on the invoked override: Spring's SpEL and MVC, Micronaut's binding,
                // Bean Validation's property paths
                .contains("public java.lang.String find" + declared + " {")
                .contains("private java.lang.String r4c$timeout0" + declared + " {")
                .contains("super.find(sku, warehouse, r4c$p2, r4c$p3, r4c$p4)")
                .contains("this.recover(sku, warehouse, r4c$p2, r4c$p3, r4c$p4)")
                .contains("async(java.lang.String sku, java.lang.String... tags)")
                // demo.Service.fixed(...) would read the parameter "demo" as its receiver
                .contains("public java.lang.String named(java.lang.String r4c$p0) {")
                .contains("return demo.Service.fixed(r4c$p0);")
                .contains("super.async(sku, tags)")
                .doesNotContain(" p0");
    }

    @Test
    void overridesDoNotRepeatBeanValidationAnnotations() {
        Compilation result = Compilation.compile(workDir, Map.of(
                "jakarta.validation.Valid", """
                        package jakarta.validation;
                        import java.lang.annotation.*;
                        @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.METHOD, ElementType.PARAMETER})
                        public @interface Valid {}
                        """,
                "jakarta.validation.Constraint", """
                        package jakarta.validation;
                        import java.lang.annotation.*;
                        @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.ANNOTATION_TYPE)
                        public @interface Constraint {}
                        """,
                "demo.Positive", """
                        package demo;
                        import java.lang.annotation.*;
                        @jakarta.validation.Constraint
                        @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.METHOD, ElementType.PARAMETER})
                        public @interface Positive {}
                        """,
                "demo.Audited", """
                        package demo;
                        import java.lang.annotation.*;
                        @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.METHOD, ElementType.PARAMETER})
                        public @interface Audited {}
                        """,
                "demo.Profiles", """
                        package demo;
                        import dev.resilience4cata.annotation.Retry;
                        import jakarta.validation.Valid;
                        public class Profiles {
                            @Retry @Valid @Positive @Audited
                            public String load(@Valid @Positive @Audited String id) { return id; }
                        }
                        """));

        assertThat(result.errors()).isEmpty();
        // Bean Validation inherits them from the overridden method, and repeating @Valid on a return value is
        // Hibernate Validator's HV000131
        assertThat(result.generatedSource("demo.Profiles$Resilient"))
                .contains("    @demo.Audited\n    public java.lang.String load(@demo.Audited java.lang.String id) {")
                .doesNotContain("Valid")
                .doesNotContain("Positive");
    }

    @Test
    void interfaceWrappersKeepTheirPositionalParameterNames() {
        Compilation result = compile("demo.Api", """
                package demo;
                import dev.resilience4cata.annotation.*;
                public interface Api {
                    @Retry
                    String find(String sku, int warehouse);
                }
                """);

        assertThat(result.errors()).isEmpty();
        assertThat(result.generatedSource("demo.Api$Resilient"))
                .contains("public java.lang.String find(java.lang.String p0, int p1) {")
                .contains("this.delegate.find(p0, p1)");
    }

    /** The part of the generated source that sets up the policy with this instance id. */
    private static String section(String generated, String instanceId) {
        int start = generated.indexOf("\"" + instanceId + "\"");
        assertThat(start).as(instanceId).isNotNegative();
        return generated.substring(start, generated.indexOf("}, ", start));
    }
}
