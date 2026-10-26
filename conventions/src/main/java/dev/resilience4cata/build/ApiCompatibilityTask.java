package dev.resilience4cata.build;

import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.CompileClasspath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.workers.WorkerExecutor;

/**
 * Compares the public API of a module with the same module in the last release ({@code r4c.apiBaseline}).
 *
 * <p>Only {@link #getPackages()} count: the packages the module exports. Public and protected members are
 * compared, binary and source compatibility both. The result is a Markdown report in
 * {@link #getReportDirectory()}, one line in the build output when something is incompatible, and a failure
 * if {@link #getFailOnIncompatible()} is set (from 1.0, within one major version: see
 * {@link PublishConventionPlugin}). A module that has no baseline (new since the last release, or the build
 * is offline) is reported as such and passes.
 *
 * <p>japicmp runs through Gradle's Worker API in an isolated class loader ({@link ApiCompatibilityWork}), so
 * its dependencies never reach the build's class path.
 */
@CacheableTask
public abstract class ApiCompatibilityTask extends DefaultTask {

    /** The baseline jar of this module: empty if it was not published or could not be resolved. */
    @Classpath
    public abstract ConfigurableFileCollection getBaselineJar();

    /** The baseline jar's dependencies, so supertypes outside the module can be resolved. */
    @Classpath
    public abstract ConfigurableFileCollection getBaselineClasspath();

    /** The jar being built. */
    @Classpath
    public abstract ConfigurableFileCollection getCurrentJar();

    /** The compile class path of the jar being built. */
    @CompileClasspath
    public abstract ConfigurableFileCollection getCurrentClasspath();

    /** japicmp and its dependencies. */
    @Classpath
    public abstract ConfigurableFileCollection getJapicmpClasspath();

    /** The packages that form the API, exactly (subpackages are listed on their own). */
    @Input
    public abstract ListProperty<String> getPackages();

    /** The baseline version, for the report. */
    @Input
    public abstract Property<String> getBaselineVersion();

    /** The version being built, for the report. */
    @Input
    public abstract Property<String> getCurrentVersion();

    /** The artifactId, for the report. */
    @Input
    public abstract Property<String> getArtifactId();

    /** Whether an incompatible change fails the build. */
    @Input
    public abstract Property<Boolean> getFailOnIncompatible();

    /** Where {@code api-changes.md} is written. */
    @OutputDirectory
    public abstract DirectoryProperty getReportDirectory();

    /**
     * The worker executor.
     *
     * @return the executor
     */
    @Inject
    protected abstract WorkerExecutor getWorkerExecutor();

    @TaskAction
    public void compare() {
        getWorkerExecutor()
                .classLoaderIsolation(spec -> spec.getClasspath().from(getJapicmpClasspath()))
                .submit(ApiCompatibilityWork.class, parameters -> {
                    parameters.getBaselineJar().from(getBaselineJar());
                    parameters.getBaselineClasspath().from(getBaselineClasspath());
                    parameters.getCurrentJar().from(getCurrentJar());
                    parameters.getCurrentClasspath().from(getCurrentClasspath());
                    parameters.getPackages().set(getPackages());
                    parameters.getBaselineVersion().set(getBaselineVersion());
                    parameters.getCurrentVersion().set(getCurrentVersion());
                    parameters.getArtifactId().set(getArtifactId());
                    parameters.getFailOnIncompatible().set(getFailOnIncompatible());
                    parameters.getReport().set(getReportDirectory().file("api-changes.md"));
                });
    }
}
