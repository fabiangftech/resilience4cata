package dev.resilience4cata.processor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.ResilienceRegistry;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import javax.annotation.processing.Processor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The processor next to Lombok, which changes the classes it compiles (constructors, getters...) in its first
 * round. Gradle and Maven run the processors in the order of the processor path, so a build may list either
 * first. Before {@link LombokRounds}, with this processor first, a class with {@code @RequiredArgsConstructor}
 * got a subclass calling {@code super()}, which no longer existed once Lombok had run; with Lombok first, a
 * {@code @Value} class still looked non-final in the first round.
 *
 * <p>Each compilation loads both processors through its own class loader, as javac loads a processor path, so
 * no Lombok state is shared between compilations.
 */
class LombokProcessorTest {

    private static final String LOMBOK = "lombok.launch.AnnotationProcessorHider$AnnotationProcessor";
    private static final String OURS = "dev.resilience4cata.processor.ResilienceProcessor";

    enum Order {
        LOMBOK_FIRST, RESILIENCE4CATA_FIRST
    }

    @TempDir
    Path workDir;

    @ParameterizedTest
    @EnumSource(Order.class)
    void aRequiredArgsConstructorIsMirroredWithItsParameters(Order order) throws Exception {
        Compilation result = compile(order, Map.of("demo.Inventory", """
                package demo;

                import dev.resilience4cata.annotation.Retry;
                import lombok.RequiredArgsConstructor;

                @RequiredArgsConstructor
                public class Inventory {
                    private final String warehouse;
                    private final int size;

                    @Retry(maxAttempts = 2)
                    public String stock(String sku) {
                        return warehouse + ":" + sku + ":" + size;
                    }
                }
                """));

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.generatedSource("demo.Inventory$Resilient"))
                .contains("public Inventory$Resilient(dev.resilience4cata.ResilienceRegistry registry, java.lang.String warehouse, int size) {")
                .contains("super(warehouse, size);");
        try (URLClassLoader loader = new URLClassLoader(new URL[] {result.classes().toUri().toURL()},
                getClass().getClassLoader())) {
            Class<?> generated = loader.loadClass("demo.Inventory$Resilient");
            Object inventory = generated.getConstructor(ResilienceRegistry.class, String.class, int.class)
                    .newInstance(ResilienceRegistry.builder().build(), "north", 3);
            Method stock = generated.getMethod("stock", String.class);
            assertThat(stock.invoke(inventory, "abc")).isEqualTo("north:abc:3");
        }
    }

    /**
     * Type-level policies protect every method the subclass may override, so the getters and setters Lombok
     * writes too (their overrides keep {@code @lombok.Generated}); {@code equals}, {@code hashCode} and
     * {@code toString} only take method-level annotations, as everywhere. The same in both orders.
     */
    @ParameterizedTest
    @EnumSource(Order.class)
    void typeLevelPoliciesCoverWhatLombokWritesInBothOrders(Order order) {
        Compilation result = compile(order, Map.of("demo.Prices", """
                package demo;

                import dev.resilience4cata.annotation.Timeout;
                import lombok.Data;

                @Data
                @Timeout("1s")
                public class Prices {
                    private String currency = "EUR";

                    public long price(String sku) {
                        return 42;
                    }
                }
                """));

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.generatedSource("demo.Prices$Resilient"))
                .contains("\"demo.Prices#price(java.lang.String)\"")
                .contains("\"demo.Prices#getCurrency()\"")
                .contains("\"demo.Prices#setCurrency(java.lang.String)\"")
                .contains("@lombok.Generated\n    public java.lang.String getCurrency() {")
                .doesNotContain("Prices#toString()")
                .doesNotContain("Prices#hashCode()");
    }

    /** A superclass compiled in the same build that uses Lombok: its getter is inherited by the annotated class. */
    @ParameterizedTest
    @EnumSource(Order.class)
    void membersLombokAddsToASuperclassAreSeen(Order order) {
        Compilation result = compile(order, Map.of("demo.Base", """
                package demo;

                @lombok.Getter
                public class Base {
                    private String region = "eu";
                }
                """, "demo.Catalog", """
                package demo;

                import dev.resilience4cata.annotation.Bulkhead;

                @Bulkhead
                public class Catalog extends Base {
                    public String find(String sku) {
                        return sku;
                    }
                }
                """));

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.generatedSource("demo.Catalog$Resilient")).contains("\"demo.Catalog#getRegion()\"");
    }

    /** {@code @Value} makes the class final in Lombok's round: reported as for any final class. */
    @ParameterizedTest
    @EnumSource(Order.class)
    void aValueClassIsReportedAsFinal(Order order) {
        Compilation result = compile(order, Map.of("demo.Rates", """
                package demo;

                import dev.resilience4cata.annotation.Retry;

                @lombok.Value
                public class Rates {
                    String base;

                    @Retry
                    public double rate(String currency) {
                        return 1.0;
                    }
                }
                """));

        assertThat(result.errorCodes()).containsExactly("R4C-SUB-001");
    }

    /**
     * A parameter's {@code @lombok.NonNull} is copied to the override, where Lombok adds its null check in the
     * next round: a null fails before the policies, with Lombok's message, and the code compiles cleanly.
     */
    @ParameterizedTest
    @EnumSource(Order.class)
    void aNonNullParameterIsCheckedOnTheOverrideToo(Order order) throws Exception {
        Compilation result = compile(order, Map.of("demo.Orders", """
                package demo;

                import dev.resilience4cata.annotation.Retry;
                import lombok.NonNull;

                public class Orders {
                    @Retry
                    public String place(@NonNull String sku) {
                        return sku;
                    }
                }
                """));

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.generatedSource("demo.Orders$Resilient")).contains("place(@lombok.NonNull java.lang.String sku)");
        try (URLClassLoader loader = new URLClassLoader(new URL[] {result.classes().toUri().toURL()},
                getClass().getClassLoader())) {
            Class<?> generated = loader.loadClass("demo.Orders$Resilient");
            Object orders = generated.getConstructor(ResilienceRegistry.class).newInstance(ResilienceRegistry.builder().build());
            Method place = generated.getMethod("place", String.class);
            assertThat(place.invoke(orders, "abc")).isEqualTo("abc");
            assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> place.invoke(orders, (Object) null)))
                    .cause().isInstanceOf(NullPointerException.class).hasMessageContaining("sku");
        }
    }

    /** The two orders write the same files. */
    @Test
    void bothOrdersGenerateTheSameCode() throws IOException {
        Map<String, String> sources = Map.of("demo.Inventory", """
                package demo;

                import dev.resilience4cata.annotation.CircuitBreaker;
                import lombok.AllArgsConstructor;
                import lombok.Getter;
                import lombok.NoArgsConstructor;

                @Getter
                @NoArgsConstructor
                @AllArgsConstructor
                @CircuitBreaker
                public class Inventory {
                    private String warehouse;

                    public String stock(String sku) {
                        return sku;
                    }
                }
                """);
        Map<String, String> lombokFirst = generated(compile(Order.LOMBOK_FIRST, sources));
        Map<String, String> oursFirst = generated(compile(Order.RESILIENCE4CATA_FIRST, sources));
        assertThat(oursFirst).isEqualTo(lombokFirst).isNotEmpty();
    }

    private int compilations;

    private Compilation compile(Order order, Map<String, String> sources) {
        List<String> names = order == Order.LOMBOK_FIRST ? List.of(LOMBOK, OURS) : List.of(OURS, LOMBOK);
        List<Path> lombok = lombokPath();
        // Not closed: javac may load processor classes until the compilation ends, and tests are short.
        URLClassLoader processorPath = new URLClassLoader(processorPath(lombok), ClassLoader.getPlatformClassLoader());
        List<Processor> processors = new ArrayList<>();
        for (String name : names) {
            try {
                processors.add((Processor) Class.forName(name, true, processorPath).getDeclaredConstructor().newInstance());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        return Compilation.compile(workDir.resolve("c" + compilations++), sources, processors, lombok,
                List.of("-Xlint:all", "-Xlint:-processing", "-Werror", "-parameters"));
    }

    /** Lombok's jar, from {@code test.lombokPath} (the build's {@code lombok} configuration). */
    private static List<Path> lombokPath() {
        String path = System.getProperty("test.lombokPath", "");
        assertThat(path).as("test.lombokPath").isNotBlank();
        return Stream.of(path.split(File.pathSeparator)).map(Path::of).toList();
    }

    /** Lombok, then the processor's classes and the annotations from the test class path. */
    private static URL[] processorPath(List<Path> lombok) {
        List<Path> entries = new ArrayList<>(lombok);
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            Path path = Path.of(entry);
            if (contains(path, "dev/resilience4cata/processor/ResilienceProcessor.class")
                    || contains(path, "dev/resilience4cata/annotation/Retry.class")) {
                entries.add(path);
            }
        }
        assertThat(entries).hasSize(lombok.size() + 2);
        List<URL> urls = new ArrayList<>();
        for (Path path : entries) {
            try {
                urls.add(path.toUri().toURL());
            } catch (MalformedURLException e) {
                throw new IllegalStateException(e);
            }
        }
        return urls.toArray(URL[]::new);
    }

    private static boolean contains(Path entry, String file) {
        if (Files.isDirectory(entry)) {
            return Files.exists(entry.resolve(file));
        }
        try (java.util.zip.ZipFile jar = new java.util.zip.ZipFile(entry.toFile())) {
            return jar.getEntry(file) != null;
        } catch (IOException e) {
            return false;
        }
    }

    private static Map<String, String> generated(Compilation compilation) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(compilation.generated())) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                files.put(compilation.generated().relativize(file).toString(), Files.readString(file));
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        return files;
    }
}
