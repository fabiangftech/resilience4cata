package dev.resilience4cata.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.TreeMap;
import java.util.stream.Stream;
import javax.annotation.processing.Processor;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Micronaut's annotation processors and resilience4cata's on one processor path, compiled in memory: the order does
 * not matter, both outputs are warning-free, and Micronaut ignores the generated classes unless they carry bean
 * annotations: with {@code -Ar4c.inject=micronaut}, or when the class's {@code @Inject} constructor is mirrored.
 */
class ProcessorCoexistenceTest {

    private static final Map<String, String> SOURCES = Map.of(
            "demo.Payments", """
                    package demo;
                    import dev.resilience4cata.annotation.Retry;
                    public interface Payments {
                        @Retry
                        int charge(int cents);
                    }
                    """,
            "demo.HttpPayments", """
                    package demo;
                    @jakarta.inject.Singleton
                    public class HttpPayments implements Payments {
                        public int charge(int cents) {
                            return cents;
                        }
                    }
                    """,
            "demo.Inventory", """
                    package demo;
                    import dev.resilience4cata.annotation.Retry;
                    @jakarta.inject.Singleton
                    public class Inventory {
                        @Retry
                        public int stock(String sku) {
                            return 1;
                        }
                    }
                    """,
            "demo.Pricing", """
                    package demo;
                    import dev.resilience4cata.annotation.Retry;
                    public class Pricing {
                        @jakarta.inject.Inject
                        public Pricing(Inventory inventory) {
                        }
                        @Retry
                        public int price(String sku) {
                            return 1;
                        }
                    }
                    """);

    @TempDir
    Path workDir;

    @Test
    void theProcessorOrderDoesNotMatter() throws IOException {
        Output micronautFirst = compile("micronaut-first", true, List.of());
        Output oursFirst = compile("ours-first", false, List.of());

        assertThat(micronautFirst.classes()).isEqualTo(oursFirst.classes());
        assertThat(micronautFirst.sources()).isEqualTo(oursFirst.sources()).containsKeys(
                "demo/Payments$Resilient.java", "demo/Inventory$Resilient.java", "demo/Pricing$Resilient.java");
    }

    @Test
    void withoutTheOptionMicronautIgnoresGeneratedClassesUnlessAnInjectConstructorIsMirrored() throws IOException {
        Output output = compile("plain", true, List.of());

        assertThat(output.classes()).contains("demo/$HttpPayments$Definition.class", "demo/$Inventory$Definition.class")
                .doesNotContain("demo/$Payments$Resilient$Definition.class", "demo/$Inventory$Resilient$Definition.class")
                // Pricing's @Inject constructor is mirrored: Pricing$Resilient is a bean too, which R4C-MN-004 reports
                .contains("demo/$Pricing$Definition.class", "demo/$Pricing$Resilient$Definition.class");
    }

    @Test
    void withTheOptionTheGeneratedSubclassIsABeanWithTheClassAnnotations() throws IOException {
        Output output = compile("option", false, List.of("-Ar4c.inject=micronaut"));

        assertThat(output.classes()).contains("demo/$Inventory$Resilient$Definition.class",
                "demo/$Pricing$Resilient$Definition.class").doesNotContain("demo/$Payments$Resilient$Definition.class");
        // the class's own annotations, and no @Replaces(Inventory.class): AnnotatedClassesFilter leaves out
        // Inventory's own definition instead, which would also replace subclasses and @MockBeans of Inventory
        assertThat(output.sources().get("demo/Inventory$Resilient.java"))
                .contains("@jakarta.inject.Singleton\n@dev.resilience4cata.generated.ContainerBean\n@SuppressWarnings")
                .doesNotContain("Replaces");
    }

    /**
     * Micronaut writes its configuration metadata file once per round, and a generated class is processed a round
     * after its class: an annotated class that is itself a configuration class ({@code @EachProperty},
     * {@code @ConfigurationProperties}) makes Micronaut warn about a second write, which fails -Werror builds. The
     * guide's alternative, configuration in its own class and policies on an {@code @EachBean} of it, compiles
     * cleanly.
     */
    @Test
    void policiesOnAnEachBeanOfAConfigurationClassCompileWithoutWarnings() throws IOException {
        Map<String, String> sources = Map.of("demo.BackendConfig", """
                package demo;
                import io.micronaut.context.annotation.EachProperty;
                import io.micronaut.context.annotation.Parameter;
                @EachProperty("backends")
                public class BackendConfig {
                    private final String name;
                    private String url;
                    public BackendConfig(@Parameter String name) {
                        this.name = name;
                    }
                    public String getName() {
                        return name;
                    }
                    public String getUrl() {
                        return url;
                    }
                    public void setUrl(String url) {
                        this.url = url;
                    }
                }
                """, "demo.Backend", """
                package demo;
                import dev.resilience4cata.annotation.Retry;
                import io.micronaut.context.annotation.EachBean;
                @EachBean(BackendConfig.class)
                public class Backend {
                    private final BackendConfig config;
                    public Backend(BackendConfig config) {
                        this.config = config;
                    }
                    @Retry(maxAttempts = 3)
                    public String call(String path) {
                        return config.getUrl() + path;
                    }
                }
                """);
        for (boolean micronautFirst : List.of(true, false)) {
            Output output = compile("each-bean-" + micronautFirst, micronautFirst, List.of("-Ar4c.inject=micronaut"),
                    sources);
            assertThat(output.classes()).contains("demo/$Backend$Resilient$Definition.class");
            assertThat(output.sources().get("demo/Backend$Resilient.java"))
                    .contains("@io.micronaut.context.annotation.EachBean(demo.BackendConfig.class)");
        }
    }

    private record Output(List<String> classes, Map<String, String> sources) {
    }

    private Output compile(String name, boolean micronautFirst, List<String> extraOptions) throws IOException {
        return compile(name, micronautFirst, extraOptions, SOURCES);
    }

    private Output compile(String name, boolean micronautFirst, List<String> extraOptions, Map<String, String> sources)
            throws IOException {
        Path dir = workDir.resolve(name);
        Path src = Files.createDirectories(dir.resolve("src"));
        Path classes = Files.createDirectories(dir.resolve("classes"));
        Path generated = Files.createDirectories(dir.resolve("generated"));
        List<Path> files = new ArrayList<>();
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = src.resolve(source.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            files.add(file);
        }
        String path = System.getProperty("test.processorPath");
        assertThat(path).as("set by the build (test.processorPath)").isNotBlank();
        try (URLClassLoader loader = new URLClassLoader(urls(path), ClassLoader.getPlatformClassLoader())) {
            List<Processor> micronaut = new ArrayList<>();
            List<Processor> ours = new ArrayList<>();
            for (Processor processor : ServiceLoader.load(Processor.class, loader)) {
                (processor.getClass().getName().startsWith("dev.resilience4cata.") ? ours : micronaut).add(processor);
            }
            assertThat(micronaut).isNotEmpty();
            assertThat(ours).hasSize(2);
            List<Processor> processors = new ArrayList<>(micronautFirst ? micronaut : ours);
            processors.addAll(micronautFirst ? ours : micronaut);

            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
                fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classes));
                fileManager.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, List.of(generated));
                List<String> options = new ArrayList<>(List.of("-classpath", path, "-proc:full",
                        "-Xlint:all", "-Xlint:-processing", "-Xlint:-classfile", "-Werror"));
                options.addAll(extraOptions);
                JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, options, null,
                        fileManager.getJavaFileObjectsFromPaths(files));
                task.setProcessors(processors);
                boolean success = task.call();
                assertThat(diagnostics.getDiagnostics()).as(name).map(d -> d.getKind() + ": " + d.getMessage(Locale.ROOT))
                        .isEmpty();
                assertThat(success).isTrue();
            }
        }
        return new Output(relative(classes, ".class").keySet().stream().toList(), relative(generated, ".java"));
    }

    private static URL[] urls(String path) {
        return Arrays.stream(path.split(File.pathSeparator)).map(entry -> {
            try {
                return Path.of(entry).toUri().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException(entry, e);
            }
        }).toArray(URL[]::new);
    }

    /** Files under {@code root} with the suffix, by relative path, with their text (sources) or empty (classes). */
    private static Map<String, String> relative(Path root, String suffix) {
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(suffix)).toList()) {
                files.put(root.relativize(file).toString().replace('\\', '/'),
                        suffix.equals(".java") ? Files.readString(file) : "");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
    }
}
