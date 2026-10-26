package dev.resilience4cata.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code -Ar4c.inject=micronaut}: the generated subclass of an annotated class repeats the class's type annotations
 * and replaces it ({@code @Replaces(C.class)}), so that Micronaut creates it in place of the class. Without the
 * option, nothing changes. Micronaut's {@code @Replaces} is declared here by a stand-in of the same name.
 */
class InjectOptionProcessorTest {

    private static final String REPLACES = """
            package io.micronaut.context.annotation;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME)
            @Target({ElementType.TYPE, ElementType.METHOD})
            public @interface Replaces {
                Class<?> value() default void.class;
            }
            """;

    @TempDir
    Path workDir;

    private static Map<String, String> sources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("io.micronaut.context.annotation.Replaces", REPLACES);
        sources.put("demo.Scope", """
                package demo;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.TYPE)
                public @interface Scope {
                }
                """);
        sources.put("demo.Requires", """
                package demo;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.METHOD})
                public @interface Requires {
                    String property() default "";
                    String[] env() default {};
                }
                """);
        sources.put("demo.Source", """
                package demo;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.SOURCE)
                @Target(ElementType.TYPE)
                public @interface Source {
                }
                """);
        sources.put("demo.Policy", """
                package demo;
                import dev.resilience4cata.annotation.*;
                import java.lang.annotation.*;
                @ResiliencePolicy
                @Retry(maxAttempts = 4)
                @Retention(RetentionPolicy.CLASS)
                @Target({ElementType.TYPE, ElementType.METHOD})
                public @interface Policy {
                }
                """);
        sources.put("demo.Other", "package demo;\npublic class Other {\n}\n");
        sources.put("demo.Api", """
                package demo;
                import dev.resilience4cata.annotation.Retry;
                public interface Api {
                    @Retry
                    String call();
                }
                """);
        sources.put("demo.Service", """
                package demo;
                import dev.resilience4cata.annotation.Retry;
                @Scope
                @Requires(property = "service.enabled", env = {"prod", "test"})
                @Source
                @Deprecated
                @Policy
                @Retry(maxAttempts = 2)
                @io.micronaut.context.annotation.Replaces(Other.class)
                public class Service {
                    @Retry
                    public String call() {
                        return "x";
                    }
                }
                """);
        return sources;
    }

    @Test
    void theSubclassRepeatsTheClassAnnotationsAndReplacesIt() {
        Compilation result = Compilation.compileWithOptions(workDir, sources(), "-Ar4c.inject=micronaut");

        assertThat(result.diagnostics()).map(d -> d.getMessage(Locale.ROOT)).isEmpty();
        assertThat(result.generatedSource("demo.Service$Resilient")).contains("""
                package demo;

                @demo.Scope
                @demo.Requires(property = "service.enabled", env = {"prod", "test"})
                @io.micronaut.context.annotation.Replaces(demo.Service.class)
                @SuppressWarnings({"deprecation", "removal", "serial"})
                public class Service$Resilient extends demo.Service {
                """);
    }

    @Test
    void withoutTheOptionTheOutputIsUnchanged() {
        Compilation with = Compilation.compileWithOptions(workDir.resolve("with"), sources(), "-Ar4c.inject=micronaut");
        Compilation without = Compilation.compile(workDir.resolve("without"), sources());
        Compilation blank = Compilation.compileWithOptions(workDir.resolve("blank"), sources(), "-Ar4c.inject=");

        assertThat(without.success()).isTrue();
        assertThat(without.generatedSource("demo.Service$Resilient")).doesNotContain("@demo.Scope", "Replaces")
                .contains("// Generated by the resilience4cata annotation processor. Do not edit.\npackage demo;\n\n"
                        + "@SuppressWarnings({\"deprecation\", \"removal\", \"serial\"})\npublic class Service$Resilient");
        assertThat(blank.generatedSource("demo.Service$Resilient")).isEqualTo(without.generatedSource("demo.Service$Resilient"));
        assertThat(with.generatedSource("demo.Api$Resilient")).as("interface wrappers never change")
                .isEqualTo(without.generatedSource("demo.Api$Resilient"));
        assertThat(with.generatedSource("demo.Service$Resilient").replaceAll("(?m)^@(demo|io\\.micronaut)\\..*\\n", ""))
                .as("only the type annotations differ").isEqualTo(without.generatedSource("demo.Service$Resilient"));
    }

    @Test
    void anUnknownValueIsAnError() {
        Compilation result = Compilation.compileWithOptions(workDir, sources(), "-Ar4c.inject=spring");

        assertThat(result.success()).isFalse();
        assertThat(result.errorCodes()).containsExactly("R4C-APT-006");
        assertThat(result.errors().get(0)).contains("'spring'", "-Ar4c.inject=micronaut");
    }

    @Test
    void micronautMissingFromTheClassPathIsAnError() {
        Map<String, String> sources = sources();
        sources.remove("io.micronaut.context.annotation.Replaces");
        sources.put("demo.Service", """
                package demo;
                import dev.resilience4cata.annotation.Retry;
                public class Service {
                    @Retry
                    public String call() {
                        return "x";
                    }
                }
                """);
        Compilation result = Compilation.compileWithOptions(workDir, sources, "-Ar4c.inject=micronaut");

        assertThat(result.errorCodes()).containsExactly("R4C-APT-006");
        assertThat(result.errors().get(0)).contains("io.micronaut.context.annotation.Replaces", "Service$Resilient");
    }
}
