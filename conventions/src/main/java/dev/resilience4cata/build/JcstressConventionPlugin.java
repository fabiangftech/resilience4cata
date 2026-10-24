package dev.resilience4cata.build;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.VersionCatalog;
import org.gradle.api.artifacts.VersionCatalogsExtension;
import org.gradle.api.file.Directory;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaToolchainService;

/**
 * jcstress tests without a third-party Gradle plugin: the jcstress annotation processor generates the
 * test harness at compile time, and the {@code jcstress} task runs {@code org.openjdk.jcstress.Main}.
 * {@code ./gradlew build} only compiles the tests; they run only when the {@code jcstress} task is asked for.
 *
 * <pre>
 * ./gradlew :stress:jcstress                                      # every test, default mode
 * ./gradlew :stress:jcstress -Pjcstress="-m quick -t CircuitBreaker"  # jcstress command-line options
 * ./gradlew :stress:jcstress -PjcstressJdk=25                     # run on another JDK
 * </pre>
 *
 * <p>Results (an HTML report per test) go to {@code build/jcstress/results}.
 */
public final class JcstressConventionPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaLibraryConventionPlugin.class);

        VersionCatalog libs = project.getExtensions().getByType(VersionCatalogsExtension.class).named("libs");
        project.getDependencies().add("implementation", libs.findLibrary("jcstress-core").orElseThrow());
        project.getDependencies().add("annotationProcessor", libs.findLibrary("jcstress-core").orElseThrow());

        Provider<String> options = project.getProviders().gradleProperty("jcstress").orElse("");
        Provider<Integer> jdk = project.getProviders().gradleProperty("jcstressJdk").map(Integer::parseInt).orElse(21);
        Provider<Directory> workDir = project.getLayout().getBuildDirectory().dir("jcstress");
        JavaToolchainService toolchains = project.getExtensions().getByType(JavaToolchainService.class);
        SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);

        project.getTasks().register("jcstress", JavaExec.class, task -> {
            task.setGroup("verification");
            task.setDescription("Runs the jcstress tests. jcstress options go in -Pjcstress=\"...\"; the JDK in -PjcstressJdk=N.");
            task.classpath(sourceSets.getByName("main").getRuntimeClasspath());
            task.getMainClass().set("org.openjdk.jcstress.Main");
            task.getJavaLauncher().set(toolchains.launcherFor(spec ->
                    spec.getLanguageVersion().set(jdk.map(JavaLanguageVersion::of))));
            // jcstress writes its binary result log to the working directory.
            task.setWorkingDir(workDir.get().getAsFile());
            task.getArgumentProviders().add(() -> {
                List<String> args = new ArrayList<>();
                String value = options.get().trim();
                if (!value.isEmpty()) {
                    args.addAll(Arrays.asList(value.split("\\s+")));
                }
                args.addAll(List.of("-r", workDir.get().dir("results").getAsFile().getAbsolutePath()));
                return args;
            });
            task.doFirst(t -> workDir.get().getAsFile().mkdirs());
        });
    }
}
