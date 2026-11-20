package dev.resilience4cata.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * The processor run by the Eclipse compiler (ECJ), which is what Eclipse, and IntelliJ when it is set to compile
 * with Eclipse, use: ECJ builds its own {@code javax.lang.model} elements and type mirrors, so whatever the processor
 * reads (element values, type printing, overriding, member order) may differ from javac.
 *
 * <p>The sources are {@link ProcessorFuzzTest}'s random cases, so they cover what the processor accepts. The
 * contract: a valid case compiles with ECJ without an error, and the processor writes exactly the files, with
 * exactly the contents, that it writes under javac (generated sources and native-image metadata); an invalid case
 * reports the same {@code [R4C-…]} codes under both compilers. One difference is allowed: a return type written
 * {@code CompletionStage<? extends Object>} is copied as {@code CompletionStage<?>}, as ECJ reads it. Seeds follow the fuzz test's properties
 * ({@code -PfuzzSeed}, {@code -PfuzzStart}); {@code -PecjCount} sets how many.
 */
class EclipseCompilerTest {

    private static final long FIRST_SEED = Long.getLong("test.fuzz.start", 1);
    private static final int DEFAULT_COUNT = 160;
    private static final int BATCH = 8;

    // Kept when the test fails: each compilation's sources and outputs, under c<n>/javac and c<n>/ecj.
    @TempDir(cleanup = CleanupMode.ON_SUCCESS)
    Path workDir;

    private int compilations;

    @Test
    void eclipseGeneratesWhatJavacGenerates() {
        String one = System.getProperty("test.seed");
        int count = Integer.getInteger("test.ecj.count", DEFAULT_COUNT);
        List<Long> seeds = one != null && !one.isBlank() ? List.of(Long.parseLong(one.trim()))
                : LongStream.range(FIRST_SEED, FIRST_SEED + count).boxed().toList();
        List<ProcessorFuzzTest.Case> cases = seeds.stream().map(seed -> new ProcessorFuzzTest.Generator(seed).generate()).toList();
        List<String> failures = new ArrayList<>();
        for (boolean valid : new boolean[] {true, false}) {
            List<ProcessorFuzzTest.Case> group = cases.stream().filter(c -> (c.expected() == null) == valid).toList();
            for (int i = 0; i < group.size() && failures.size() < 5; i += BATCH) {
                List<ProcessorFuzzTest.Case> batch = group.subList(i, Math.min(group.size(), i + BATCH));
                List<String> problems = check(batch);
                if (!problems.isEmpty() && batch.size() > 1) {
                    List<String> alone = new ArrayList<>();
                    for (ProcessorFuzzTest.Case c : batch) {
                        alone.addAll(check(List.of(c)));
                    }
                    problems = alone.isEmpty() ? problems : alone;
                }
                failures.addAll(problems);
            }
        }
        System.out.printf(Locale.ROOT, "EclipseCompilerTest: %d seeds from %d in %d compilations%n", cases.size(),
                seeds.getFirst(), compilations);
        assertThat(failures).as("differences between ECJ and javac").isEmpty();
    }

    /**
     * The differences the fuzz seeds found, written out: before they were fixed, ECJ numbered the fields of
     * overloads in another order (its {@code getAllMembers} order), named the instance of a method taking
     * {@code Holder<String>.Inner} {@code …(Holder<V>.Inner)}, rejected generic fallbacks whose bounds have a
     * wildcard or an intersection ({@code isSameType} is false for nested wildcards in ECJ), and did not find
     * the interface that declares a generic method of an annotated class ({@code Elements.overrides} is false
     * for generic methods in ECJ), and failed to compile the generated {@code this.delegate.count(p0)} for a
 * {@code Collection<? super T>} parameter (it cannot infer {@code T}: the call now passes {@code <T>}).
     */
    @Test
    void theDifferencesTheSeedsFoundStayFixed() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("ecj.Holder", """
                package ecj;

                public class Holder<V> {
                    public class Inner {
                    }
                }
                """);
        sources.put("ecj.Api", """
                package ecj;

                import dev.resilience4cata.annotation.CircuitBreaker;
                import dev.resilience4cata.annotation.Fallback;
                import dev.resilience4cata.annotation.Retry;
                import org.jspecify.annotations.Nullable;

                @CircuitBreaker
                public interface Api {

                    @Retry
                    String inner(Holder<String>.Inner inner);

                    @Fallback(method = "smallest")
                    <T extends Comparable<? super T>> @Nullable T first(java.util.List<T> values);

                    default <T extends Comparable<? super T>> @Nullable T smallest(java.util.List<T> values, Exception e) {
                        return null;
                    }

                    default void overload(int x, Throwable t) {
                    }

                    default void overload(int x) {
                    }

                    String last();

                    @Retry
                    <T extends Number> int count(java.util.Collection<? super T> into);
                }
                """);
        sources.put("ecj.Contract", """
                package ecj;

                import dev.resilience4cata.annotation.Fallback;
                import java.util.concurrent.CompletionStage;

                public interface Contract {

                    @Fallback(method = "same")
                    <T extends Number & Comparable<T>> CompletionStage<T> max(T value);

                    static <T extends Number & Comparable<T>> T same(T value, Exception e) {
                        return value;
                    }
                }
                """);
        sources.put("ecj.Service", """
                package ecj;

                import dev.resilience4cata.annotation.Bulkhead;
                import java.util.concurrent.CompletableFuture;
                import java.util.concurrent.CompletionStage;

                @Bulkhead
                public class Service implements Contract {

                    @Override
                    public <T extends Number & Comparable<T>> CompletionStage<T> max(T value) {
                        return CompletableFuture.completedFuture(value);
                    }
                }
                """);
        Path dir = workDir.resolve("written");
        Compilation javac = Compilation.compile(dir.resolve("javac"), sources);
        Compilation ecj = Compilation.compileWithEclipse(dir.resolve("ecj"), sources, List.of("-warn:none"));
        assertThat(javac.errors()).isEmpty();
        assertThat(differences("written sources (" + dir + ")", javac, ecj)).isEmpty();
        assertThat(ecj.generatedSource("ecj.Api$Resilient")).contains("\"ecj.Api#inner(ecj.Holder.Inner)\"");
        assertThat(ecj.generatedSource("ecj.Service$Resilient")).contains("this.fallback0");
    }

    private List<String> check(List<ProcessorFuzzTest.Case> batch) {
        Map<String, String> sources = new LinkedHashMap<>();
        batch.forEach(c -> sources.putAll(c.sources()));
        Path dir = workDir.resolve("c" + compilations++);
        String seeds = "seeds " + batch.stream().map(c -> String.valueOf(c.seed())).collect(Collectors.joining(", "))
                + " (" + dir + ")";
        Compilation javac = Compilation.compile(dir.resolve("javac"), sources);
        // -proceedOnError: ECJ would otherwise stop at the first erroneous unit, javac reports them all.
        Compilation ecj = Compilation.compileWithEclipse(dir.resolve("ecj"), sources, List.of("-warn:none", "-proceedOnError"));
        List<String> problems = new ArrayList<>();
        boolean valid = batch.stream().allMatch(c -> c.expected() == null);
        if (valid) {
            if (!javac.success()) {
                problems.add(seeds + ": javac failed, see ProcessorFuzzTest: " + javac.errors());
                return problems;
            }
            problems.addAll(differences(seeds, javac, ecj));
        } else {
            // Only our codes: javac stops after the processor's errors, ECJ also reports the compiler's own.
            Set<String> fromJavac = ours(javac);
            Set<String> fromEcj = ours(ecj);
            if (!fromJavac.equals(fromEcj)) {
                problems.add(seeds + ": ECJ reported " + fromEcj + ", javac " + fromJavac + "\nECJ: " + describe(ecj));
            }
        }
        return problems;
    }

    /** What ECJ did differently from javac with valid sources: an error, or a generated file that is not the same. */
    private static List<String> differences(String label, Compilation javac, Compilation ecj) {
        List<String> problems = new ArrayList<>();
        if (!ecj.success() || !ecj.errors().isEmpty()) {
            problems.add(label + ": ECJ failed: " + describe(ecj));
        }
        Map<String, String> fromJavac = files(javac);
        Map<String, String> fromEcj = files(ecj);
        if (!fromJavac.keySet().equals(fromEcj.keySet())) {
            problems.add(label + ": ECJ generated " + fromEcj.keySet() + ", javac " + fromJavac.keySet());
        }
        fromJavac.forEach((file, text) -> {
            // ECJ reads a written "? extends Object" as "?", the same type, and the copied signature shows it so.
            String expected = text.replace("<? extends java.lang.Object>", "<?>");
            String other = fromEcj.get(file);
            if (other != null && !other.equals(expected)) {
                problems.add(label + ": " + file + " differs under ECJ:\n" + firstDifference(expected, other));
            }
        });
        return problems;
    }

    private static Set<String> ours(Compilation compilation) {
        return compilation.errorCodes().stream().filter(code -> code.startsWith("R4C-"))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static String describe(Compilation compilation) {
        return compilation.diagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(EclipseCompilerTest::where)
                .collect(Collectors.joining("\n"));
    }

    private static String where(Diagnostic<? extends JavaFileObject> d) {
        String source = d.getSource() == null ? "" : d.getSource().getName() + ":" + d.getLineNumber() + ": ";
        return source + d.getMessage(Locale.ROOT);
    }

    /** Every file the processor wrote: generated sources and native-image metadata, by relative path. */
    private static Map<String, String> files(Compilation compilation) {
        Map<String, String> files = new TreeMap<>();
        read(compilation.generated(), compilation.generated(), files);
        Path metadata = compilation.classes().resolve("META-INF/native-image");
        read(compilation.classes(), metadata, files);
        return files;
    }

    private static void read(Path root, Path dir, Map<String, String> into) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                into.put(root.relativize(file).toString().replace('\\', '/'), Files.readString(file));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String firstDifference(String expected, String actual) {
        String[] a = expected.split("\n", -1);
        String[] b = actual.split("\n", -1);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            String x = i < a.length ? a[i] : "<end>";
            String y = i < b.length ? b[i] : "<end>";
            if (!x.equals(y)) {
                return "line " + (i + 1) + "\n  javac: " + x + "\n  ECJ:   " + y;
            }
        }
        return "(same lines)";
    }
}
