package dev.resilience4cata.build;

import java.util.List;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.VersionCatalog;
import org.gradle.api.artifacts.VersionCatalogsExtension;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.plugins.BasePluginExtension;
import org.gradle.api.plugins.JavaLibraryPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.javadoc.Javadoc;
import org.gradle.api.tasks.testing.Test;
import org.gradle.external.javadoc.StandardJavadocDocletOptions;
import org.gradle.api.provider.Provider;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.jvm.toolchain.JavaToolchainService;

/**
 * Shared setup for every Java module: Java 21, strict compiler, JUnit tests.
 *
 * <p>Tests run on the Java 21 toolchain; {@code -PtestJdk=25} runs them on another JDK (the code is
 * still compiled with {@code --release 21}), which is how CI tests on 21 and 25.
 */
public final class JavaLibraryConventionPlugin implements Plugin<Project> {

    private static final int JAVA_VERSION = 21;

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaLibraryPlugin.class);

        JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
        java.getToolchain().getLanguageVersion().set(JavaLanguageVersion.of(JAVA_VERSION));
        java.withSourcesJar();
        java.withJavadocJar();

        project.getExtensions().getByType(BasePluginExtension.class)
                .getArchivesName().set("resilience4cata-" + project.getName());

        project.getTasks().withType(JavaCompile.class).configureEach(task -> {
            task.getOptions().getRelease().set(JAVA_VERSION);
            task.getOptions().setEncoding("UTF-8");
            task.getOptions().getCompilerArgs().addAll(List.of("-Xlint:all", "-Xlint:-processing", "-Werror"));
        });

        project.getTasks().withType(Javadoc.class).configureEach(task ->
                ((StandardJavadocDocletOptions) task.getOptions()).addBooleanOption("Xdoclint:all,-missing", true));

        JavaToolchainService toolchains = project.getExtensions().getByType(JavaToolchainService.class);
        Provider<JavaLauncher> testLauncher = project.getProviders().gradleProperty("testJdk")
                .map(Integer::parseInt)
                .flatMap(version -> toolchains.launcherFor(spec -> spec.getLanguageVersion().set(JavaLanguageVersion.of(version))));
        project.getTasks().withType(Test.class).configureEach(task -> {
            task.useJUnitPlatform();
            if (testLauncher.isPresent()) {
                task.getJavaLauncher().set(testLauncher);
            }
        });

        addTestDependencies(project);
    }

    private static void addTestDependencies(Project project) {
        VersionCatalog libs = project.getExtensions().getByType(VersionCatalogsExtension.class).named("libs");
        DependencyHandler deps = project.getDependencies();
        deps.add("testImplementation", deps.platform(libs.findLibrary("junit-bom").orElseThrow()));
        deps.add("testImplementation", libs.findLibrary("junit-jupiter").orElseThrow());
        deps.add("testImplementation", libs.findLibrary("assertj").orElseThrow());
        deps.add("testRuntimeOnly", libs.findLibrary("junit-launcher").orElseThrow());
    }
}
