package dev.resilience4cata.build;

import java.util.List;
import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.process.CommandLineArgumentProvider;

/**
 * Passes a class path to a test JVM as a system property ({@code -D<name>=<path>}), tracked as an input of the
 * task and compatible with the configuration cache. For tests that compile sources in memory against jars that
 * are not on their own class path, such as annotation processors:
 *
 * <pre>{@code
 * tasks.named('test') {
 *     jvmArgumentProviders.add(new dev.resilience4cata.build.ClasspathProperty('test.processorPath', configurations.processorPath))
 * }
 * }</pre>
 */
public final class ClasspathProperty implements CommandLineArgumentProvider {

    private final String name;
    private final FileCollection path;

    /**
     * Creates the provider.
     *
     * @param name the system property's name
     * @param path the class path
     */
    public ClasspathProperty(String name, FileCollection path) {
        this.name = name;
        this.path = path;
    }

    /**
     * The system property's name.
     *
     * @return the name
     */
    @Input
    public String getName() {
        return name;
    }

    /**
     * The class path.
     *
     * @return the files
     */
    @Classpath
    public FileCollection getPath() {
        return path;
    }

    @Override
    public Iterable<String> asArguments() {
        return List.of("-D" + name + "=" + path.getAsPath());
    }
}
