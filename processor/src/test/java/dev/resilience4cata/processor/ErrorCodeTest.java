package dev.resilience4cata.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.processing.Completion;
import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checks the error reference pages ({@code docs/en/errors.md}, {@code docs/es/errors.md}) against
 * {@link ProcessorError}: every code is unique and has a section in both languages, and every code is
 * emitted. For most codes the proof is the page itself: the first {@code java} block of the code's section
 * (the wrong example) must fail with that code, and the second one (the fix), if any, must compile cleanly.
 */
class ErrorCodeTest {

    private static final Pattern HEADING = Pattern.compile("(?m)^### (R4C-[A-Z]+-\\d{3})$");
    private static final Pattern JAVA_BLOCK = Pattern.compile("(?ms)^```java\\n(.*?)^```$");
    private static final Pattern FILE_COMMENT = Pattern.compile("(?m)^(?=// [\\w/]+\\.java$)");
    private static final Pattern PACKAGE = Pattern.compile("(?m)^package ([\\w.]+);");
    private static final Pattern TOP_LEVEL_TYPE = Pattern.compile(
            "(?m)^(?:(?:public|final|abstract|sealed|non-sealed)\\s+)*(?:@interface|interface|class|record|enum)\\s+(\\w+)");
    /** Areas of the processor's codes; the other areas on the pages belong to core (RT) and config (CFG). */
    private static final Set<String> AREAS = Set.of("APT", "ATR", "CMP", "ASY", "CLS", "FB", "SUB");
    /** Codes whose wrong example needs a classpath without resilience4cata-core. */
    private static final Set<ProcessorError> WITHOUT_CORE =
            Set.of(ProcessorError.CLASSIFIER_MISSING_CORE, ProcessorError.HANDLER_MISSING_CORE);

    @TempDir
    Path workDir;

    @Test
    void codesAreUniqueAndWellFormed() {
        List<String> codes = Arrays.stream(ProcessorError.values()).map(ProcessorError::code).toList();
        assertThat(codes).doesNotHaveDuplicates()
                .allSatisfy(code -> assertThat(code).matches("R4C-[A-Z]+-\\d{3}").satisfies(c -> assertThat(AREAS)
                        .contains(area(c))));
        assertThat(Arrays.stream(ProcessorError.values()).map(ProcessorError::summary)).doesNotHaveDuplicates();
    }

    @Test
    void bothPagesDocumentTheSameCodesInTheSameOrderWithTheSameExamples() throws IOException {
        String english = page("en");
        String spanish = page("es");
        List<String> codes = headings(english);
        assertThat(codes).doesNotHaveDuplicates().isEqualTo(headings(spanish));
        for (String code : codes) {
            assertThat(javaBlocks(section(spanish, code))).as(code).isEqualTo(javaBlocks(section(english, code)));
        }
        assertThat(codes.stream().filter(c -> AREAS.contains(area(c))))
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(ProcessorError.values()).map(ProcessorError::code).toList());
    }

    @TestFactory
    Stream<DynamicTest> everyCodeIsEmittedByItsDocumentedExample() throws IOException {
        String english = page("en");
        return Arrays.stream(ProcessorError.values())
                .filter(error -> error != ProcessorError.UNKNOWN_ATTRIBUTE && error != ProcessorError.WRITE_FAILED)
                .map(error -> DynamicTest.dynamicTest(error.code(), () -> {
                    List<String> blocks = javaBlocks(section(english, error.code()));
                    assertThat(blocks).as("java examples of " + error.code()).isNotEmpty().hasSizeLessThanOrEqualTo(2);
                    Path dir = Files.createDirectories(workDir.resolve(error.code()));
                    Map<String, String> wrong = unit(blocks.get(0));
                    Compilation failing = WITHOUT_CORE.contains(error)
                            ? Compilation.compileWithoutCore(dir.resolve("wrong"), wrong)
                            : Compilation.compile(dir.resolve("wrong"), wrong);
                    assertThat(failing.errorCodes()).as("errors of the wrong example").contains(error.code());
                    if (blocks.size() == 2) {
                        Compilation fixed = Compilation.compile(dir.resolve("fixed"), unit(blocks.get(1)));
                        assertThat(fixed.diagnostics()).as("diagnostics of the fix").isEmpty();
                        assertThat(fixed.success()).isTrue();
                    }
                }));
    }

    @Test
    void reportsAttributesItDoesNotKnow() {
        // An annotations jar newer than the processor: @Retry has an attribute the processor does not handle.
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("dev.resilience4cata.annotation.Retry", """
                package dev.resilience4cata.annotation;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.CLASS)
                @Target({ElementType.METHOD, ElementType.TYPE})
                public @interface Retry {
                    int hedges() default 0;
                }
                """);
        sources.put("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.Retry;
                public interface Client {
                    @Retry(hedges = 2)
                    String call();
                }
                """);
        Compilation result = Compilation.compile(workDir, sources);
        assertThat(result.errors()).containsExactly("[R4C-APT-004] Unknown attribute @Retry.hedges. The processor and"
                + " the annotations jar have different versions.");
        assertThat(section(pageUnchecked(), ProcessorError.UNKNOWN_ATTRIBUTE.code())).contains("@Retry");
    }

    @Test
    void reportsAGeneratedFileThatCannotBeWritten() {
        Compilation result = Compilation.compile(workDir, Map.of("demo.Client", """
                package demo;
                import dev.resilience4cata.annotation.Retry;
                public interface Client {
                    @Retry
                    String call();
                }
                """), new FailingFiler(new ResilienceProcessor()), List.of());
        assertThat(result.errorCodes()).containsExactly(ProcessorError.WRITE_FAILED.code());
        assertThat(result.errors().get(0)).contains("Could not write demo.Client$Resilient: disk full.");
    }

    private static String area(String code) {
        return code.split("-")[1];
    }

    private static String page(String language) throws IOException {
        return Files.readString(Path.of("..", "docs", language, "errors.md"));
    }

    private static String pageUnchecked() {
        try {
            return page("en");
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static List<String> headings(String page) {
        return HEADING.matcher(page).results().map(m -> m.group(1)).toList();
    }

    /** The text of a code's section: from its heading to the next heading of the same or a higher level. */
    private static String section(String page, String code) {
        int start = page.indexOf("\n### " + code + "\n");
        assertThat(start).as("section of " + code).isNotNegative();
        Matcher next = Pattern.compile("(?m)^#{1,3} ").matcher(page);
        int end = next.find(start + 5) ? next.start() : page.length();
        return page.substring(start, end);
    }

    private static List<String> javaBlocks(String section) {
        return JAVA_BLOCK.matcher(section).results().map(m -> m.group(1)).collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * The compilation units of an example: one, or several each introduced by a {@code // path/Name.java}
     * comment line. Each is named after its package and its public top-level type (or its first one).
     */
    private static Map<String, String> unit(String block) {
        Map<String, String> units = new LinkedHashMap<>();
        for (String source : FILE_COMMENT.split(block)) {
            if (source.isBlank()) {
                continue;
            }
            Matcher pkg = PACKAGE.matcher(source);
            List<MatchResult> types = TOP_LEVEL_TYPE.matcher(source).results().toList();
            assertThat(types).as("top-level types in\n" + source).isNotEmpty();
            String type = types.stream().filter(m -> m.group().startsWith("public")).findFirst().orElse(types.get(0))
                    .group(1);
            units.put((pkg.find() ? pkg.group(1) + "." : "") + type, source);
        }
        return units;
    }

    /** Runs a processor with a {@link Filer} whose {@code createSourceFile} always fails. */
    private static final class FailingFiler implements Processor {

        private final Processor delegate;

        FailingFiler(Processor delegate) {
            this.delegate = delegate;
        }

        @Override
        public Set<String> getSupportedOptions() {
            return delegate.getSupportedOptions();
        }

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return delegate.getSupportedAnnotationTypes();
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return delegate.getSupportedSourceVersion();
        }

        @Override
        public void init(ProcessingEnvironment environment) {
            Filer filer = proxy(Filer.class, environment.getFiler(), "createSourceFile");
            InvocationHandler handler = (p, method, args) -> method.getName().equals("getFiler") ? filer
                    : invoke(method, environment, args);
            delegate.init((ProcessingEnvironment) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {ProcessingEnvironment.class}, handler));
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            return delegate.process(annotations, roundEnv);
        }

        @Override
        public Iterable<? extends Completion> getCompletions(Element element, AnnotationMirror annotation,
                ExecutableElement member, String userText) {
            return delegate.getCompletions(element, annotation, member, userText);
        }

        private static <T> T proxy(Class<T> type, T target, String failing) {
            InvocationHandler handler = (p, method, args) -> {
                if (method.getName().equals(failing)) {
                    throw new IOException("disk full");
                }
                return invoke(method, target, args);
            };
            return type.cast(Proxy.newProxyInstance(FailingFiler.class.getClassLoader(), new Class<?>[] {type}, handler));
        }

        private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }
}
