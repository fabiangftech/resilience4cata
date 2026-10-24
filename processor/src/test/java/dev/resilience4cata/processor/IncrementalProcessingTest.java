package dev.resilience4cata.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checks what Gradle needs to treat the processor as isolating: the registration resource, exactly one
 * originating element per generated file (the interface it wraps, or the class it extends), and output that
 * depends only on that type and what it references, so recompiling the type alone, with everything else read
 * from class files, generates the same source.
 */
class IncrementalProcessingTest {

    private static final String REGISTRATION = "META-INF/gradle/incremental.annotation.processors";

    private static final Map<String, String> SOURCES = Map.ofEntries(
            Map.entry("demo.Standard", """
                    package demo;
                    import dev.resilience4cata.annotation.CircuitBreaker;
                    import dev.resilience4cata.annotation.ResiliencePolicy;
                    import dev.resilience4cata.annotation.Retry;
                    import java.lang.annotation.*;
                    @ResiliencePolicy
                    @Retry(maxAttempts = 3)
                    @CircuitBreaker(windowSize = 20)
                    @Retention(RetentionPolicy.CLASS)
                    @Target({ElementType.TYPE, ElementType.METHOD})
                    public @interface Standard {}
                    """),
            Map.entry("demo.Handler", """
                    package demo;
                    import dev.resilience4cata.FallbackContext;
                    import dev.resilience4cata.FallbackHandler;
                    public class Handler implements FallbackHandler<String> {
                        @Override public String apply(FallbackContext context) { return "fallback"; }
                    }
                    """),
            Map.entry("demo.Client", """
                    package demo;
                    import dev.resilience4cata.annotation.Fallback;
                    @Standard
                    public interface Client {
                        @Fallback(Handler.class)
                        String call(String argument);
                        @Fallback(method = "recover")
                        String other();
                        default String recover(IllegalStateException e) { return "recovered"; }
                    }
                    """),
            Map.entry("demo.Base", """
                    package demo;
                    import dev.resilience4cata.annotation.Timeout;
                    public interface Base {
                        @Timeout("2s")
                        int base();
                    }
                    """),
            Map.entry("demo.Sub", """
                    package demo;
                    public interface Sub extends Base {
                        int own();
                        interface Nested extends Base {}
                    }
                    """),
            Map.entry("demo.BaseService", """
                    package demo;
                    import dev.resilience4cata.annotation.Retry;
                    public class BaseService {
                        @Retry(maxAttempts = 2) public String inherited() { return ""; }
                        public String second() { return ""; }
                        protected String recover(Exception e) { return "recovered"; }
                    }
                    """),
            Map.entry("demo.Service", """
                    package demo;
                    import dev.resilience4cata.annotation.Fallback;
                    @Standard
                    public class Service extends BaseService implements Base {
                        public Service(String name) {}
                        Service() {}
                        @Fallback(Handler.class) public String call(String argument) { return argument; }
                        @Fallback(method = "recover") public String other() { return ""; }
                        @Override public int base() { return 1; }
                    }
                    """),
            Map.entry("demo.Plain", "package demo; public class Plain {}"));

    @TempDir
    Path workDir;

    @Test
    void registersTheProcessorAsIsolating() throws IOException {
        List<URL> resources = Collections.list(getClass().getClassLoader().getResources(REGISTRATION));
        assertThat(resources).hasSize(1);
        assertThat(lines(resources.get(0))).containsExactly(ResilienceProcessor.class.getName() + ",isolating");

        List<URL> services = Collections.list(getClass().getClassLoader()
                .getResources("META-INF/services/" + Processor.class.getName()));
        assertThat(services).hasSize(1);
        assertThat(lines(services.get(0))).containsExactly(ResilienceProcessor.class.getName());
    }

    @Test
    void everyGeneratedFileHasTheWrappedInterfaceAsItsOnlyOriginatingElement() {
        RecordingProcessor processor = new RecordingProcessor();
        Compilation result = Compilation.compile(workDir, SOURCES, processor, List.of());

        assertThat(result.errors()).isEmpty();
        assertThat(processor.otherFilerCalls).isEmpty();
        assertThat(processor.origins).containsOnly(
                Map.entry("demo.Client$Resilient", List.of("demo.Client")),
                Map.entry("demo.Base$Resilient", List.of("demo.Base")),
                Map.entry("demo.Sub$Resilient", List.of("demo.Sub")),
                Map.entry("demo.Sub$Nested$Resilient", List.of("demo.Sub.Nested")),
                Map.entry("demo.BaseService$Resilient", List.of("demo.BaseService")),
                Map.entry("demo.Service$Resilient", List.of("demo.Service")));
    }

    @Test
    void recompilingOnlyTheInterfaceGeneratesTheSameSource() throws IOException {
        Compilation full = Compilation.compile(workDir.resolve("full"), SOURCES);
        assertThat(full.errors()).isEmpty();

        // What Gradle does when only Client.java and Sub.java must be recompiled: their outputs are deleted,
        // and their dependencies (the bundle, the handler, the superinterface) are read from class files.
        Path previous = workDir.resolve("previous");
        try (Stream<Path> files = Files.walk(full.classes())) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (name.startsWith("Client") || name.startsWith("Sub") || name.startsWith("Service")) {
                    continue;
                }
                Path target = previous.resolve(full.classes().relativize(file));
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
            }
        }
        Compilation partial = Compilation.compile(workDir.resolve("partial"),
                Map.of("demo.Client", SOURCES.get("demo.Client"), "demo.Sub", SOURCES.get("demo.Sub"),
                        "demo.Service", SOURCES.get("demo.Service")),
                new ResilienceProcessor(), List.of(previous));

        assertThat(partial.errors()).isEmpty();
        for (String generated : List.of("demo.Client$Resilient", "demo.Sub$Resilient", "demo.Sub$Nested$Resilient",
                "demo.Service$Resilient")) {
            assertThat(partial.generatedSource(generated)).isEqualTo(full.generatedSource(generated));
        }
        assertThat(Files.exists(partial.generated().resolve("demo/Base$Resilient.java"))).isFalse();
        assertThat(Files.exists(partial.generated().resolve("demo/BaseService$Resilient.java"))).isFalse();
        // the subclass merges what it inherits from the superclass and the interface, both read from class files
        assertThat(partial.generatedSource("demo.Service$Resilient"))
                .contains("extends demo.Service {")
                .contains("\"demo.Service#inherited()\"")
                .contains("\"demo.Service#base()\"")
                .contains("return this.recover(r4c$f0);")
                .contains("public Service$Resilient(dev.resilience4cata.ResilienceRegistry registry, java.lang.String p0)");
    }

    private static List<String> lines(URL url) throws IOException {
        try (InputStream in = url.openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
        }
    }

    /** Runs the real processor with a {@link Filer} that records the originating elements of each file. */
    private static final class RecordingProcessor implements Processor {

        private final ResilienceProcessor delegate = new ResilienceProcessor();
        final Map<String, List<String>> origins = new LinkedHashMap<>();
        final List<String> otherFilerCalls = new ArrayList<>();

        @Override
        public void init(ProcessingEnvironment env) {
            Filer filer = env.getFiler();
            Filer recording = proxy(Filer.class, (proxy, method, args) -> {
                if (method.getName().equals("createSourceFile")) {
                    List<String> names = new ArrayList<>();
                    for (Element element : (Element[]) args[1]) {
                        names.add(((TypeElement) element).getQualifiedName().toString());
                    }
                    origins.put(args[0].toString(), names);
                } else {
                    otherFilerCalls.add(method.getName() + Arrays.toString(args));
                }
                return invoke(filer, method, args);
            });
            delegate.init(proxy(ProcessingEnvironment.class, (proxy, method, args) ->
                    method.getName().equals("getFiler") ? recording : invoke(env, method, args)));
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
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            return delegate.process(annotations, roundEnv);
        }

        @Override
        public Iterable<? extends Completion> getCompletions(Element element, AnnotationMirror annotation,
                ExecutableElement member, String userText) {
            return delegate.getCompletions(element, annotation, member, userText);
        }

        private static <T> T proxy(Class<T> type, InvocationHandler handler) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
        }

        private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
