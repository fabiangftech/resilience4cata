package dev.resilience4cata.build;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.VersionCatalog;
import org.gradle.api.artifacts.VersionCatalogsExtension;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaToolchainService;

/**
 * JMH benchmarks without a third-party Gradle plugin: the JMH annotation processor generates the
 * harness at compile time, and the {@code jmh} task runs {@code org.openjdk.jmh.Main}.
 *
 * <pre>
 * ./gradlew :benchmark:jmh                                   # every benchmark, default settings
 * ./gradlew :benchmark:jmh -Pjmh="Retry -prof gc"            # JMH command-line options
 * ./gradlew :benchmark:jmh -PjmhJdk=25                       # run on another JDK
 * ./gradlew :benchmark:allocationCheck                       # allocation gate (see below)
 * </pre>
 *
 * <p>If the project has an {@code allocation-budgets.properties} file, {@code allocationCheck} runs
 * every benchmark it lists with short settings and {@code -prof gc}, then fails if one allocates more
 * bytes per operation than its budget ({@link AllocationCheck}). Timing on a shared machine is noise,
 * but bytes per operation are deterministic enough to gate on. {@code -PallocationJmh="..."} replaces
 * the short run settings; {@code -PjmhJdk=N} applies too.
 */
public final class JmhConventionPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaLibraryConventionPlugin.class);

        VersionCatalog libs = project.getExtensions().getByType(VersionCatalogsExtension.class).named("libs");
        project.getDependencies().add("implementation", libs.findLibrary("jmh-core").orElseThrow());
        project.getDependencies().add("annotationProcessor", libs.findLibrary("jmh-annprocess").orElseThrow());

        Provider<String> options = project.getProviders().gradleProperty("jmh").orElse("");
        Provider<Integer> jdk = project.getProviders().gradleProperty("jmhJdk").map(Integer::parseInt).orElse(21);
        Provider<RegularFile> results = project.getLayout().getBuildDirectory().file("jmh/results.json");
        JavaToolchainService toolchains = project.getExtensions().getByType(JavaToolchainService.class);
        SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);

        project.getTasks().register("jmh", JavaExec.class, task -> {
            task.setGroup("benchmark");
            task.setDescription("Runs the JMH benchmarks. JMH options go in -Pjmh=\"...\"; the JDK in -PjmhJdk=N.");
            task.classpath(sourceSets.getByName("main").getRuntimeClasspath());
            task.getMainClass().set("org.openjdk.jmh.Main");
            task.getJavaLauncher().set(toolchains.launcherFor(spec ->
                    spec.getLanguageVersion().set(jdk.map(JavaLanguageVersion::of))));
            task.getArgumentProviders().add(() -> {
                List<String> args = new ArrayList<>();
                String value = options.get().trim();
                if (!value.isEmpty()) {
                    args.addAll(Arrays.asList(value.split("\\s+")));
                }
                args.addAll(List.of("-rf", "json", "-rff", results.get().getAsFile().getAbsolutePath()));
                return args;
            });
            task.doFirst(t -> results.get().getAsFile().getParentFile().mkdirs());
        });

        File budgets = project.file(BUDGETS);
        if (budgets.isFile()) {
            registerAllocationCheck(project, budgets, jdk, toolchains, sourceSets);
        }
    }

    /** Budgets of the allocation gate, relative to the project directory. */
    static final String BUDGETS = "allocation-budgets.properties";

    /** Short run: bytes per operation settle within a few hundred milliseconds; timing is ignored. */
    private static final String ALLOCATION_RUN = "-f 1 -wi 3 -w 200ms -i 3 -r 200ms -t 1";

    private static void registerAllocationCheck(Project project, File budgets, Provider<Integer> jdk,
            JavaToolchainService toolchains, SourceSetContainer sourceSets) {
        Provider<String> run = project.getProviders().gradleProperty("allocationJmh").orElse(ALLOCATION_RUN);
        Provider<RegularFile> results = project.getLayout().getBuildDirectory().file("jmh/allocation.json");

        TaskProvider<JavaExec> benchmarks = project.getTasks().register("allocationBenchmarks", JavaExec.class, task -> {
            task.setGroup("benchmark");
            task.setDescription("Runs the benchmarks listed in " + BUDGETS + " with -prof gc and short settings.");
            task.classpath(sourceSets.getByName("main").getRuntimeClasspath());
            task.getMainClass().set("org.openjdk.jmh.Main");
            task.getJavaLauncher().set(toolchains.launcherFor(spec ->
                    spec.getLanguageVersion().set(jdk.map(JavaLanguageVersion::of))));
            task.getInputs().file(budgets).withPropertyName("budgets");
            task.getArgumentProviders().add(() -> {
                List<String> args = new ArrayList<>(AllocationCheck.includes(budgets.toPath()));
                args.addAll(Arrays.asList(run.get().trim().split("\\s+")));
                // Fail on any benchmark error instead of skipping it.
                args.addAll(List.of("-prof", "gc", "-foe", "true",
                        "-rf", "json", "-rff", results.get().getAsFile().getAbsolutePath()));
                return args;
            });
            task.doFirst(t -> results.get().getAsFile().getParentFile().mkdirs());
        });

        project.getTasks().register("allocationCheck", AllocationCheck.class, task -> {
            task.setGroup("verification");
            task.setDescription("Fails if a benchmark listed in " + BUDGETS + " allocates more than its budget per operation.");
            task.dependsOn(benchmarks);
            task.getBudgets().set(budgets);
            task.getResults().set(results);
            task.getReport().set(project.getLayout().getBuildDirectory().file("jmh/allocation-report.md"));
        });
    }
}
