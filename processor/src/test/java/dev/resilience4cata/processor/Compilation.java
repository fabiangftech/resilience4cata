package dev.resilience4cata.processor;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import javax.annotation.processing.Processor;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/** Compiles Java sources with the processor, using only the JDK compiler API. */
record Compilation(boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics, Path generated,
        Path classes) {

    static Compilation compile(Path workDir, Map<String, String> sources) {
        return compile(workDir, sources, new ResilienceProcessor(), List.of());
    }

    /**
     * Compiles {@code sources} with {@code processor}; {@code classpath} entries are added before the test
     * classpath, so classes compiled earlier can stand in for sources that are not recompiled.
     */
    static Compilation compile(Path workDir, Map<String, String> sources, Processor processor, List<Path> classpath) {
        String path = Stream.concat(classpath.stream().map(Path::toString),
                Stream.of(System.getProperty("java.class.path"))).collect(Collectors.joining(File.pathSeparator));
        return compile(workDir, sources, processor, path);
    }

    /** Compiles {@code sources} with the processor and extra compiler {@code options}, such as {@code -Akey=value}. */
    static Compilation compileWithOptions(Path workDir, Map<String, String> sources, String... options) {
        List<String> all = new ArrayList<>(List.of("-Xlint:all", "-Xlint:-processing", "-Werror"));
        all.addAll(List.of(options));
        return compile(workDir, sources, List.of(new ResilienceProcessor()), System.getProperty("java.class.path"), all);
    }

    /** Compiles {@code sources} with the processor, on the test classpath without resilience4cata-core. */
    static Compilation compileWithoutCore(Path workDir, Map<String, String> sources) {
        String path = Stream.of(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(entry -> !containsCore(Path.of(entry)))
                .collect(Collectors.joining(File.pathSeparator));
        return compile(workDir, sources, new ResilienceProcessor(), path);
    }

    private static boolean containsCore(Path entry) {
        String classifier = "dev/resilience4cata/Classifier.class";
        if (Files.isDirectory(entry)) {
            return Files.exists(entry.resolve(classifier));
        }
        try (ZipFile jar = new ZipFile(entry.toFile())) {
            return jar.getEntry(classifier) != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Compiles {@code sources} on the test classpath with several processors, in order, and the given
     * compiler options instead of the default lint options (which turn {@code -Xlint:processing} off).
     */
    static Compilation compile(Path workDir, Map<String, String> sources, List<Processor> processors, List<String> lint) {
        return compile(workDir, sources, processors, System.getProperty("java.class.path"), lint);
    }

    private static Compilation compile(Path workDir, Map<String, String> sources, Processor processor, String path) {
        return compile(workDir, sources, List.of(processor), path, List.of("-Xlint:all", "-Xlint:-processing", "-Werror"));
    }

    private static Compilation compile(Path workDir, Map<String, String> sources, List<Processor> processors, String path,
            List<String> lint) {
        try {
            Path sourceDir = Files.createDirectories(workDir.resolve("src"));
            Path classes = Files.createDirectories(workDir.resolve("classes"));
            Path generated = Files.createDirectories(workDir.resolve("generated"));
            List<Path> files = new ArrayList<>();
            for (Map.Entry<String, String> source : sources.entrySet()) {
                Path file = sourceDir.resolve(source.getKey().replace('.', '/') + ".java");
                Files.createDirectories(file.getParent());
                Files.writeString(file, source.getValue());
                files.add(file);
            }
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
                fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classes));
                fileManager.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, List.of(generated));
                List<String> options = new ArrayList<>(List.of("-classpath", path));
                options.addAll(lint);
                JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, options, null,
                        fileManager.getJavaFileObjectsFromPaths(files));
                task.setProcessors(processors);
                boolean success = task.call();
                return new Compilation(success, diagnostics.getDiagnostics(), generated, classes);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    List<String> errors() {
        return diagnostics.stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(d -> d.getMessage(Locale.ROOT))
                .toList();
    }

    /** The code of each error, in order: {@code R4C-FB-002} for {@code [R4C-FB-002] @Fallback(...)}. */
    List<String> errorCodes() {
        return errors().stream()
                .map(e -> e.startsWith("[R4C-") && e.indexOf(']') > 0 ? e.substring(1, e.indexOf(']')) : "(no code) " + e)
                .toList();
    }

    /** The GraalVM reachability metadata the processor wrote for the generated class {@code generatedName}. */
    String nativeImageMetadata(String generatedName) {
        try {
            return Files.readString(classes.resolve("META-INF/native-image/dev.resilience4cata.generated/" + generatedName
                    + "/reachability-metadata.json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String generatedSource(String qualifiedName) {
        try {
            return Files.readString(generated.resolve(qualifiedName.replace('.', '/') + ".java"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
