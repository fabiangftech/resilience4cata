package dev.resilience4cata.build;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.VersionCatalog;
import org.gradle.api.artifacts.VersionCatalogsExtension;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.language.jvm.tasks.ProcessResources;

/**
 * The parts of a Quarkus extension that Quarkus's own Maven and Gradle extension plugins would produce, done with
 * plain Gradle (the {@code io.quarkus.extension} plugin is experimental and is not one of the build's allowed
 * plugins). Applied after {@code r4c.java-library} to both projects of the extension, with a role per project name:
 *
 * <ul>
 *   <li>{@code runtime}: {@code quarkusExtensionDescriptor} writes {@code META-INF/quarkus-extension.properties}
 *       ({@code deployment-artifact}, which is how Quarkus finds the deployment module) and
 *       {@code META-INF/quarkus-extension.yaml} (the template {@code src/main/quarkus-extension.yaml} completed with
 *       the artifact and the Quarkus version) into the jar's resources. They are generated outside the source
 *       resource directories on purpose: the Quarkus Gradle plugin then reads them from the jar of a project
 *       dependency, like any other extension, and resolves the deployment artifact by its coordinates.
 *   <li>{@code deployment}: {@code quarkusExtensionCheck} (part of {@code check}) fails when an extension on the
 *       runtime class path has no deployment artifact on the deployment module's class path, the check
 *       Quarkus's Maven plugin does.
 * </ul>
 *
 * <p>The extension processor runs with {@code -AgenerateDoc=false}: its config documentation needs a
 * {@code pom.xml}. The Quarkus core version written to the descriptor is {@code quarkusMinimum} from the version catalog.
 */
public final class QuarkusExtensionConventionPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        // quarkus-extension-processor (META-INF/quarkus-build-steps.list, config roots) generates the Quarkus config
        // documentation only from a pom.xml; without one it warns, which -Werror turns into an error.
        project.getTasks().named("compileJava", JavaCompile.class).configure(task ->
                task.getOptions().getCompilerArgs().add("-AgenerateDoc=false"));
        switch (project.getName()) {
            case "runtime" -> runtime(project);
            case "deployment" -> deployment(project);
            default -> throw new GradleException("r4c.quarkus-extension: " + project.getPath()
                    + " is neither the runtime nor the deployment project of an extension");
        }
    }

    private static void runtime(Project project) {
        VersionCatalog libs = project.getExtensions().getByType(VersionCatalogsExtension.class).named("libs");
        String quarkus = libs.findVersion("quarkusMinimum").orElseThrow().getRequiredVersion();
        String group = project.getGroup().toString();
        String version = project.getVersion().toString();
        String artifact = JavaLibraryConventionPlugin.artifactName(project);
        TaskProvider<QuarkusExtensionDescriptorTask> descriptor = project.getTasks().register(
                "quarkusExtensionDescriptor", QuarkusExtensionDescriptorTask.class, task -> {
                    task.setDescription("Writes META-INF/quarkus-extension.properties and quarkus-extension.yaml.");
                    task.getTemplate().set(project.getLayout().getProjectDirectory().file("src/main/quarkus-extension.yaml"));
                    task.getArtifact().set(group + ":" + artifact + "::jar:" + version);
                    task.getDeploymentArtifact().set(group + ":" + artifact + "-deployment:" + version);
                    task.getQuarkusCoreVersion().set(quarkus);
                    task.getMinimumJavaVersion().set("21");
                    task.getOutputDirectory().set(project.getLayout().getBuildDirectory().dir("generated/quarkus-extension"));
                });
        project.getTasks().named("processResources", ProcessResources.class).configure(task -> task.from(descriptor));
    }

    private static void deployment(Project project) {
        Configuration classpath = project.getConfigurations().getByName("runtimeClasspath");
        TaskProvider<QuarkusExtensionCheckTask> check = project.getTasks().register(
                "quarkusExtensionCheck", QuarkusExtensionCheckTask.class, task -> {
                    task.setDescription("Checks that every extension on the class path has its deployment artifact.");
                    task.getClasspath().from(classpath);
                    task.getComponents().set(classpath.getIncoming().getResolutionResult().getRootComponent()
                            .map(QuarkusExtensionCheckTask::modules));
                    task.getSelf().set(project.getGroup() + ":" + JavaLibraryConventionPlugin.artifactName(project));
                    task.getMarker().set(project.getLayout().getBuildDirectory().file("quarkus-extension-check"));
                });
        project.getTasks().named("check").configure(task -> task.dependsOn(check));
    }
}
