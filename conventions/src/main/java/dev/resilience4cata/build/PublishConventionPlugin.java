package dev.resilience4cata.build;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.Directory;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPom;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.plugins.signing.Sign;
import org.gradle.plugins.signing.SigningExtension;
import org.gradle.plugins.signing.SigningPlugin;

/**
 * Publication of a module to Maven Central, with Gradle's own {@code maven-publish} and
 * {@code signing} plugins only.
 *
 * <p>Applied by every published module, after {@code r4c.java-library} (which already adds the
 * sources and Javadoc jars) or {@code java-platform} (the {@code bom}). It creates one publication,
 * {@code mavenJava}, with artifactId {@code resilience4cata-<module>} and the POM fields Central
 * requires; puts {@code LICENSE} and {@code NOTICE} in every jar's {@code META-INF}; makes archives
 * reproducible; and adds a {@code centralStaging} file repository, {@code build/central-staging} at
 * the root, that {@code r4c.central} zips into the upload bundle.
 *
 * <p>Signing uses in-memory ASCII-armored keys from the Gradle properties {@code signingInMemoryKey}
 * and {@code signingInMemoryKeyPassword} (or the environment variables
 * {@code ORG_GRADLE_PROJECT_signingInMemoryKey} and {@code ORG_GRADLE_PROJECT_signingInMemoryKeyPassword}).
 * Without a key nothing is signed, so {@code build} and {@code publishToMavenLocal} need no secrets;
 * {@code centralBundle} then refuses to build a bundle with missing signatures.
 *
 * <p>The SCM coordinates come from the {@code r4c.scmRepository} property ({@code owner/repo} on
 * GitHub) and the developer from {@code r4c.developerId} and {@code r4c.developerName}, all in
 * {@code gradle.properties}.
 */
public final class PublishConventionPlugin implements Plugin<Project> {

    static final String PUBLICATION = "mavenJava";
    static final String STAGING_REPOSITORY = "centralStaging";
    static final String STAGING_DIR = "central-staging";
    private static final String URL = "https://resilience4cata.dev";

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(MavenPublishPlugin.class);
        project.getPluginManager().apply(SigningPlugin.class);

        ProviderFactory providers = project.getProviders();
        String scmRepository = providers.gradleProperty("r4c.scmRepository").get();
        String developerId = providers.gradleProperty("r4c.developerId").get();
        String developerName = providers.gradleProperty("r4c.developerName").get();
        Provider<Directory> staging = project.getRootProject().getLayout().getBuildDirectory().dir(STAGING_DIR);

        PublishingExtension publishing = project.getExtensions().getByType(PublishingExtension.class);
        MavenPublication publication = publishing.getPublications().create(PUBLICATION, MavenPublication.class);
        publication.setArtifactId(JavaLibraryConventionPlugin.artifactName(project));
        project.getPluginManager().withPlugin("java", plugin ->
                publication.from(project.getComponents().getByName("java")));
        project.getPluginManager().withPlugin("java-platform", plugin ->
                publication.from(project.getComponents().getByName("javaPlatform")));
        publication.pom(pom -> describe(project, pom, scmRepository, developerId, developerName));

        publishing.getRepositories().maven(repository -> {
            repository.setName(STAGING_REPOSITORY);
            repository.setUrl(staging);
        });

        configureSigning(project, publication);

        RegularFile license = project.getRootProject().getLayout().getProjectDirectory().file("LICENSE");
        RegularFile notice = project.getRootProject().getLayout().getProjectDirectory().file("NOTICE");
        project.getTasks().withType(Jar.class).configureEach(jar -> jar.metaInf(spec -> spec.from(license, notice)));
        project.getTasks().withType(AbstractArchiveTask.class).configureEach(archive -> {
            archive.setPreserveFileTimestamps(false);
            archive.setReproducibleFileOrder(true);
        });
    }

    private static void describe(Project project, MavenPom pom, String scmRepository, String developerId, String developerName) {
        pom.getName().set(JavaLibraryConventionPlugin.artifactName(project));
        pom.getDescription().set(project.provider(() -> {
            String description = project.getDescription();
            if (description == null || description.isBlank()) {
                throw new IllegalStateException(project.getPath() + " has no description: Maven Central requires one in the POM");
            }
            return description;
        }));
        pom.getUrl().set(URL);
        pom.licenses(licenses -> licenses.license(license -> {
            license.getName().set("Apache-2.0");
            license.getUrl().set("https://www.apache.org/licenses/LICENSE-2.0.txt");
            license.getDistribution().set("repo");
        }));
        pom.developers(developers -> developers.developer(developer -> {
            developer.getId().set(developerId);
            developer.getName().set(developerName);
            developer.getUrl().set("https://github.com/" + developerId);
        }));
        pom.scm(scm -> {
            scm.getUrl().set("https://github.com/" + scmRepository);
            scm.getConnection().set("scm:git:https://github.com/" + scmRepository + ".git");
            scm.getDeveloperConnection().set("scm:git:ssh://git@github.com/" + scmRepository + ".git");
        });
        pom.issueManagement(issues -> {
            issues.getSystem().set("GitHub");
            issues.getUrl().set("https://github.com/" + scmRepository + "/issues");
        });
    }

    private static void configureSigning(Project project, MavenPublication publication) {
        ProviderFactory providers = project.getProviders();
        Provider<String> key = providers.gradleProperty("signingInMemoryKey");
        Provider<String> password = providers.gradleProperty("signingInMemoryKeyPassword").orElse("");
        SigningExtension signing = project.getExtensions().getByType(SigningExtension.class);
        // Not required: without a key the Sign tasks are skipped. centralBundle checks the signatures.
        signing.setRequired(false);
        if (key.isPresent()) {
            String keyId = providers.gradleProperty("signingInMemoryKeyId").getOrNull();
            if (keyId == null) {
                signing.useInMemoryPgpKeys(key.get(), password.get());
            } else {
                signing.useInMemoryPgpKeys(keyId, key.get(), password.get());
            }
            signing.sign(publication);
        }
        project.getTasks().withType(Sign.class).configureEach(task -> task.setGroup("publishing"));
    }
}
