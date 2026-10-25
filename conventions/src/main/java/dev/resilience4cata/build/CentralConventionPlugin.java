package dev.resilience4cata.build;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.Delete;
import org.gradle.api.tasks.TaskProvider;

/**
 * Maven Central release tasks, applied to the root project. No third-party plugin: the bundle is
 * built from the {@code centralStaging} repositories that {@code r4c.publish} adds to every
 * published module, and uploaded with the Central Portal Publisher API.
 *
 * <pre>
 * ./gradlew centralBundle -Pversion=0.1.0      # build/central-bundle.zip, signed and checked
 * ./gradlew centralUpload -Pversion=0.1.0      # upload it as a USER_MANAGED deployment
 * </pre>
 *
 * <p>{@code centralBundle} first deletes {@code build/central-staging}, then publishes every module
 * that applies {@code r4c.publish} into it, then runs {@link CentralBundleTask}. {@code centralUpload}
 * ({@link CentralUploadTask}) is only run when asked for by name.
 */
public final class CentralConventionPlugin implements Plugin<Project> {

    private static final String PUBLISH_TASK = "publish" + capitalize(PublishConventionPlugin.PUBLICATION)
            + "PublicationTo" + capitalize(PublishConventionPlugin.STAGING_REPOSITORY) + "Repository";

    @Override
    public void apply(Project project) {
        if (project != project.getRootProject()) {
            throw new IllegalStateException("r4c.central must be applied to the root project");
        }
        ProviderFactory providers = project.getProviders();
        String version = project.getVersion().toString();

        TaskProvider<Delete> clean = project.getTasks().register("centralStagingClean", Delete.class, task -> {
            task.setGroup("publishing");
            task.setDescription("Deletes the local Central staging repository.");
            task.delete(project.getLayout().getBuildDirectory().dir(PublishConventionPlugin.STAGING_DIR));
        });

        TaskProvider<CentralBundleTask> bundle = project.getTasks().register("centralBundle", CentralBundleTask.class, task -> {
            task.setGroup("publishing");
            task.setDescription("Publishes every module to build/central-staging and zips it into build/central-bundle.zip.");
            task.dependsOn(clean);
            task.getStagingDirectory().set(project.getLayout().getBuildDirectory().dir(PublishConventionPlugin.STAGING_DIR));
            task.getGroupPath().set(project.getGroup().toString().replace('.', '/'));
            task.getVersion().set(version);
            task.getBundle().set(project.getLayout().getBuildDirectory().file("central-bundle.zip"));
        });

        project.getTasks().register("centralUpload", CentralUploadTask.class, task -> {
            task.setGroup("publishing");
            task.setDescription("Uploads build/central-bundle.zip to the Central Portal as a USER_MANAGED deployment (never released automatically).");
            task.getBundle().set(bundle.flatMap(CentralBundleTask::getBundle));
            task.getDeploymentName().set(project.getName() + "-" + version);
            task.getPortalUrl().set(providers.gradleProperty("centralPortalUrl").orElse("https://central.sonatype.com"));
            task.getUsername().set(providers.gradleProperty("centralPortalUsername"));
            task.getPassword().set(providers.gradleProperty("centralPortalPassword"));
        });

        project.getSubprojects().forEach(module -> module.getPluginManager().withPlugin("r4c.publish", plugin -> {
            String path = module.getPath() + ":" + PUBLISH_TASK;
            bundle.configure(task -> {
                task.dependsOn(path);
                task.getArtifactIds().add("resilience4cata-" + module.getName());
            });
            module.getTasks().named(PUBLISH_TASK).configure(task -> task.mustRunAfter(clean));
        }));
    }

    private static String capitalize(String name) {
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
