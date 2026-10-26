package dev.resilience4cata.build;

import org.gradle.testing.jacoco.plugins.JacocoPlugin;
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension;
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
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.jvm.toolchain.JavaToolchainService;

/**
 * Shared setup for every Java module: Java 21, strict compiler, JUnit tests.
 *
 * <p>JaCoCo is applied but its agent only runs with {@code -Pcoverage}.
 *
 * <p>Tests run on the Java 21 toolchain; {@code -PtestJdk=25} runs them on another JDK (the code is
 * still compiled with {@code --release 21}), which is how CI tests on 21 and 25.
 *
 * <p>A module that needs a newer Java (the documented exception is {@code micronaut}: Micronaut 5 requires
 * Java 25) sets {@code r4cJava.release = 25} in its build file: its toolchain and {@code --release} follow, and
 * its tests run on {@code testJdk} only when that is at least the release, otherwise on the release's
 * toolchain (so {@code -PtestJdk=21} still builds, testing that module on 25).
 */
public final class JavaLibraryConventionPlugin implements Plugin<Project> {

    private static final int JAVA_VERSION = 21;

    /** The {@code r4cJava} extension: the Java release of one module. */
    public abstract static class JavaReleaseExtension {

        /** Creates the extension. */
        public JavaReleaseExtension() {
        }

        /**
         * The {@code --release} and toolchain version, 21 unless the module says otherwise.
         *
         * @return the release
         */
        public abstract Property<Integer> getRelease();
    }

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaLibraryPlugin.class);

        JavaReleaseExtension javaRelease = project.getExtensions().create("r4cJava", JavaReleaseExtension.class);
        javaRelease.getRelease().convention(JAVA_VERSION);
        Provider<Integer> release = javaRelease.getRelease();

        JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
        java.getToolchain().getLanguageVersion().set(release.map(JavaLanguageVersion::of));
        java.withSourcesJar();
        java.withJavadocJar();

        project.getExtensions().getByType(BasePluginExtension.class)
                .getArchivesName().set(artifactName(project));

        project.getTasks().withType(JavaCompile.class).configureEach(task -> {
            task.getOptions().getRelease().set(release);
            task.getOptions().setEncoding("UTF-8");
            task.getOptions().getCompilerArgs().addAll(List.of("-Xlint:all", "-Xlint:-processing", "-Werror"));
        });

        project.getTasks().withType(Javadoc.class).configureEach(task ->
                ((StandardJavadocDocletOptions) task.getOptions()).addBooleanOption("Xdoclint:all,-missing", true));

        JavaToolchainService toolchains = project.getExtensions().getByType(JavaToolchainService.class);
        Provider<JavaLauncher> testLauncher = project.getProviders().gradleProperty("testJdk")
                .map(Integer::parseInt)
                .zip(release, Math::max) // never below the module's release
                .flatMap(version -> toolchains.launcherFor(spec -> spec.getLanguageVersion().set(JavaLanguageVersion.of(version))));
        project.getTasks().withType(Test.class).configureEach(task -> {
            task.useJUnitPlatform();
            if (testLauncher.isPresent()) {
                task.getJavaLauncher().set(testLauncher);
            }
        });

        // Coverage only on request (-Pcoverage, used by the SonarCloud analysis): the JaCoCo agent stays off
        // in ordinary builds, so tests that measure allocations or timing run on uninstrumented code.
        project.getPluginManager().apply(JacocoPlugin.class);
        boolean coverage = project.getProviders().gradleProperty("coverage").isPresent();
        project.getTasks().withType(Test.class).configureEach(task ->
                task.getExtensions().getByType(JacocoTaskExtension.class).setEnabled(coverage));

        addTestDependencies(project);
    }

    /**
     * The artifact name of a module: {@code resilience4cata-<module>}. A nested project (only the Quarkus
     * extension has them) is named after its parent, plus its own name unless it is {@code runtime}, as Quarkus
     * names extension artifacts: {@code :quarkus:runtime} is {@code resilience4cata-quarkus},
     * {@code :quarkus:deployment} is {@code resilience4cata-quarkus-deployment}.
     */
    static String artifactName(Project project) {
        Project parent = project.getParent();
        if (parent == null || parent.getParent() == null) {
            return "resilience4cata-" + project.getName();
        }
        String own = project.getName().equals("runtime") ? "" : "-" + project.getName();
        return "resilience4cata-" + parent.getName() + own;
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
