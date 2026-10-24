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
                String path = Stream.concat(classpath.stream().map(Path::toString),
                        Stream.of(System.getProperty("java.class.path"))).collect(Collectors.joining(File.pathSeparator));
                List<String> options = List.of("-classpath", path,
                        "-Xlint:all", "-Xlint:-processing", "-Werror");
                JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, options, null,
                        fileManager.getJavaFileObjectsFromPaths(files));
                task.setProcessors(List.of(processor));
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
                .collect(Collectors.toList());
    }

    String generatedSource(String qualifiedName) {
        try {
            return Files.readString(generated.resolve(qualifiedName.replace('.', '/') + ".java"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
