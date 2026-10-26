package dev.resilience4cata.build;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Zips the {@code centralStaging} repository into the bundle the Central Portal Publisher API
 * accepts, after checking it against Central's requirements.
 *
 * <p>Only the files of {@link #getVersion()} go in (no {@code maven-metadata.xml}, no other versions
 * left in the staging directory). The task fails if the version is a SNAPSHOT, if an expected
 * artifact is missing, or if any file lacks its {@code .asc} signature or its {@code .md5} and
 * {@code .sha1} checksums; and, for a module with a jar, if its sources or Javadoc jar is missing.
 * The {@code .sha256} and {@code .sha512} checksums Gradle also writes are left out: Central does not
 * require them, and every file in the bundle counts against the Central Portal's monthly file quota.
 * Entries are sorted and carry a fixed timestamp, so the same staging content gives the same zip.
 */
public abstract class CentralBundleTask extends DefaultTask {

    private static final Set<String> SIDE_FILES = Set.of(".asc", ".md5", ".sha1", ".sha256", ".sha512");
    // Optional for Central and counted in the monthly file quota: not uploaded.
    private static final Set<String> LEFT_OUT = Set.of(".sha256", ".sha512");
    // Local time, as Gradle's reproducible archives use: independent of the machine's time zone.
    private static final LocalDateTime FIXED_TIME = LocalDateTime.of(1980, 2, 1, 0, 0);

    /** The {@code centralStaging} Maven repository. */
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getStagingDirectory();

    /** The groupId, as a path ({@code dev/resilience4cata}). */
    @Input
    public abstract Property<String> getGroupPath();

    /** The version being released. */
    @Input
    public abstract Property<String> getVersion();

    /** The artifactIds that must be in the bundle. */
    @Input
    public abstract SetProperty<String> getArtifactIds();

    /** The bundle zip. */
    @OutputFile
    public abstract RegularFileProperty getBundle();

    @TaskAction
    public void bundle() throws IOException {
        String version = getVersion().get();
        if (version.endsWith("-SNAPSHOT")) {
            throw new GradleException("Maven Central does not accept SNAPSHOT versions (" + version
                    + "). Build the bundle with a release version, e.g. ./gradlew centralBundle -Pversion=0.1.0");
        }
        Path root = getStagingDirectory().get().getAsFile().toPath();
        Path group = root.resolve(getGroupPath().get());

        List<String> problems = new ArrayList<>();
        Set<String> entries = new TreeSet<>();
        for (String artifactId : new TreeSet<>(getArtifactIds().get())) {
            Path dir = group.resolve(artifactId).resolve(version);
            if (!Files.isDirectory(dir)) {
                problems.add(artifactId + ": not published to " + root.relativize(dir));
                continue;
            }
            Set<String> names = new TreeSet<>();
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(Files::isRegularFile).forEach(file -> names.add(file.getFileName().toString()));
            }
            String base = artifactId + "-" + version;
            require(names, base + ".pom", artifactId, problems);
            if (names.contains(base + ".jar")) {
                require(names, base + "-sources.jar", artifactId, problems);
                require(names, base + "-javadoc.jar", artifactId, problems);
            }
            for (String name : names) {
                if (!isSideFile(name)) {
                    require(names, name + ".asc", artifactId, problems);
                    require(names, name + ".md5", artifactId, problems);
                    require(names, name + ".sha1", artifactId, problems);
                }
                if (hasExtension(name, LEFT_OUT)) {
                    continue;
                }
                entries.add(root.relativize(dir.resolve(name)).toString().replace('\\', '/'));
            }
        }
        if (!problems.isEmpty()) {
            throw new GradleException("The Central bundle is incomplete:\n  " + String.join("\n  ", problems)
                    + "\nMissing .asc files mean no signing key was set: pass signingInMemoryKey and"
                    + " signingInMemoryKeyPassword (Gradle properties or ORG_GRADLE_PROJECT_ environment variables).");
        }

        Path zip = getBundle().get().getAsFile().toPath();
        Files.createDirectories(zip.getParent());
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream zipOut = new ZipOutputStream(out)) {
            for (String entry : entries) {
                ZipEntry zipEntry = new ZipEntry(entry);
                zipEntry.setTimeLocal(FIXED_TIME);
                zipOut.putNextEntry(zipEntry);
                Files.copy(root.resolve(entry), zipOut);
                zipOut.closeEntry();
            }
        }
        getLogger().lifecycle("Central bundle: {} ({} files)", zip, entries.size());
    }

    private static boolean isSideFile(String name) {
        return hasExtension(name, SIDE_FILES);
    }

    private static boolean hasExtension(String name, Set<String> extensions) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && extensions.contains(name.substring(dot));
    }

    private static void require(Set<String> names, String name, String artifactId, List<String> problems) {
        if (!names.contains(name)) {
            problems.add(artifactId + ": missing " + name);
        }
    }
}
