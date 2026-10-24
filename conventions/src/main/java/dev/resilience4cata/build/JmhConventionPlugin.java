package dev.resilience4cata.build;

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
 * </pre>
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
    }
}
