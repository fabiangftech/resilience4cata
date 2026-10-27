package dev.resilience4cata.build;

import japicmp.cli.JApiCli;
import japicmp.cmp.JApiCmpArchive;
import japicmp.cmp.JarArchiveComparator;
import japicmp.cmp.JarArchiveComparatorOptions;
import japicmp.config.Options;
import japicmp.model.AccessModifier;
import japicmp.model.JApiChangeStatus;
import japicmp.model.JApiClass;
import japicmp.model.JApiClassType;
import japicmp.model.JApiMethod;
import japicmp.output.markdown.MarkdownOutputGenerator;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javassist.ClassPool;
import javassist.bytecode.AnnotationDefaultAttribute;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;

/**
 * The japicmp comparison of {@link ApiCompatibilityTask}, run in a class loader that has japicmp on it.
 */
public abstract class ApiCompatibilityWork implements WorkAction<ApiCompatibilityWork.Parameters> {

    private static final Logger LOGGER = Logging.getLogger(ApiCompatibilityWork.class);

    /** The task's inputs. */
    public interface Parameters extends WorkParameters {

        /** @return the baseline jar, empty when there is none */
        ConfigurableFileCollection getBaselineJar();

        /** @return the baseline jar's dependencies */
        ConfigurableFileCollection getBaselineClasspath();

        /** @return the jar being built */
        ConfigurableFileCollection getCurrentJar();

        /** @return its compile class path */
        ConfigurableFileCollection getCurrentClasspath();

        /** @return the API packages */
        ListProperty<String> getPackages();

        /** @return the baseline version */
        Property<String> getBaselineVersion();

        /** @return the version being built */
        Property<String> getCurrentVersion();

        /** @return the artifactId */
        Property<String> getArtifactId();

        /** @return whether an incompatible change fails the build */
        Property<Boolean> getFailOnIncompatible();

        /** @return the Markdown report */
        RegularFileProperty getReport();
    }

    @Override
    public void execute() {
        Parameters parameters = getParameters();
        String artifactId = parameters.getArtifactId().get();
        String baseline = parameters.getBaselineVersion().get();
        String current = parameters.getCurrentVersion().get();
        Path report = parameters.getReport().get().getAsFile().toPath();

        if (parameters.getBaselineJar().isEmpty()) {
            write(report, "# " + artifactId + "\n\nNo " + baseline + " to compare with: the module is new since "
                    + baseline + ", or the build could not reach Maven Central.\n");
            LOGGER.lifecycle("{}: no {} to compare the API with (new module, or offline)", artifactId, baseline);
            return;
        }

        // javassist caches every jar it opens in the JVM-wide jar URL cache, which outlives this build in the
        // Gradle daemon: a jar rebuilt at the same path would then be read through its stale entry.
        ClassPool.cacheOpenedJarFile = false;
        Options options = Options.newDefault();
        options.setAccessModifier(AccessModifier.PROTECTED);
        options.setOutputOnlyModifications(true);
        options.setIgnoreMissingClasses(true);
        options.addIncludeFromArgument(Optional.of(String.join(";", parameters.getPackages().get())), true);
        options.setClassPathMode(JApiCli.ClassPathMode.TWO_SEPARATE_CLASSPATHS);
        options.setOldClassPath(Optional.of(path(parameters.getBaselineClasspath())));
        options.setNewClassPath(Optional.of(path(parameters.getCurrentClasspath())));
        List<JApiCmpArchive> oldArchives = archives(parameters.getBaselineJar(), baseline);
        List<JApiCmpArchive> newArchives = archives(parameters.getCurrentJar(), current);
        options.setOldArchives(oldArchives);
        options.setNewArchives(newArchives);

        List<JApiClass> classes = new JarArchiveComparator(JarArchiveComparatorOptions.of(options))
                .compare(oldArchives, newArchives);
        List<String> binary = names(classes.stream().filter(c -> !c.isBinaryCompatible()).toList());
        List<String> source = names(classes.stream().filter(c -> c.isBinaryCompatible() && sourceIncompatible(c)).toList());

        StringBuilder summary = new StringBuilder("# ").append(artifactId).append(": ").append(baseline)
                .append(" → ").append(current).append("\n\n");
        if (binary.isEmpty() && source.isEmpty()) {
            summary.append("No incompatible change in the API.\n");
        } else {
            list(summary, "Binary incompatible (also source incompatible, as a rule)", binary);
            list(summary, "Source incompatible only", source);
        }
        if (classes.stream().anyMatch(c -> !c.isSourceCompatible() && !sourceIncompatible(c))) {
            summary.append("\nAn element added to an annotation with a default value is source compatible, though"
                    + " japicmp's report below lists it as an abstract method added.\n");
        }
        summary.append("\n## Every change\n\n").append(new MarkdownOutputGenerator(options, classes).generate());
        write(report, summary.toString());

        if (binary.isEmpty() && source.isEmpty()) {
            return;
        }
        String message = artifactId + ": " + binary.size() + " binary and " + source.size()
                + " source incompatible API change(s) since " + baseline + ": " + String.join(", ", concat(binary, source))
                + ". Report: " + report.toUri();
        if (parameters.getFailOnIncompatible().get()) {
            throw new GradleException(message + "\nA breaking change needs a new major version (see docs/en/releasing.md).");
        }
        LOGGER.warn("{}\nAllowed before 1.0: list it in the changelog.", message);
    }

    /**
     * Whether {@code type} breaks source compatibility. japicmp treats an annotation like an interface, so
     * an element added with a default value counts as an abstract method added, which breaks
     * implementations; nobody implements an annotation, and uses that do not set the element still compile.
     */
    private static boolean sourceIncompatible(JApiClass type) {
        if (type.isSourceCompatible()) {
            return false;
        }
        if (!isAnnotation(type)) {
            return true;
        }
        if (type.getCompatibilityChanges().stream().anyMatch(change -> !change.isSourceCompatible())) {
            return true;
        }
        return type.getMethods().stream()
                .filter(element -> element.getChangeStatus() != JApiChangeStatus.NEW || !hasDefault(element))
                .anyMatch(element -> !element.isSourceCompatible());
    }

    private static boolean isAnnotation(JApiClass type) {
        JApiClassType classType = type.getClassType();
        return classType.getOldTypeOptional().orElse(null) == JApiClassType.ClassType.ANNOTATION
                && classType.getNewTypeOptional().orElse(null) == JApiClassType.ClassType.ANNOTATION;
    }

    private static boolean hasDefault(JApiMethod element) {
        return element.getNewMethod()
                .map(method -> method.getMethodInfo().getAttribute(AnnotationDefaultAttribute.tag) != null)
                .orElse(false);
    }

    private static List<JApiCmpArchive> archives(ConfigurableFileCollection jars, String version) {
        return jars.getFiles().stream().map(jar -> new JApiCmpArchive(jar, version)).toList();
    }

    private static String path(ConfigurableFileCollection files) {
        return files.getFiles().stream().map(File::getAbsolutePath).collect(Collectors.joining(File.pathSeparator));
    }

    private static List<String> names(List<JApiClass> classes) {
        return classes.stream().map(JApiClass::getFullyQualifiedName).sorted().toList();
    }

    private static List<String> concat(List<String> first, List<String> second) {
        return Stream.concat(first.stream(), second.stream()).toList();
    }

    private static void list(StringBuilder out, String title, List<String> names) {
        if (names.isEmpty()) {
            return;
        }
        out.append("## ").append(title).append("\n\n");
        names.forEach(name -> out.append("- `").append(name).append("`\n"));
        out.append('\n');
    }

    private static void write(Path report, String text) {
        try {
            Files.createDirectories(report.getParent());
            Files.writeString(report, text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
