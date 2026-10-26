package dev.resilience4cata.build;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.VersionCatalog;
import org.gradle.api.artifacts.VersionCatalogsExtension;
import org.gradle.api.attributes.Bundling;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.attributes.Usage;
import org.gradle.api.file.Directory;
import org.gradle.api.file.FileCollection;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
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
 *
 * <p>Every published module with a jar also gets {@code apiCompatibility} ({@link ApiCompatibilityTask}, run by
 * {@code check}): japicmp compares the jar with the same artifact at {@code r4c.apiBaseline} (gradle.properties,
 * the last release) from Maven Central. The API is the packages the module exports in {@code module-info.java}
 * (qualified exports excluded), or, for a module without one, {@code r4cApi.packages} in its build file; a module
 * with no API package (the processor, the Quarkus deployment module) is skipped. Incompatible changes are a
 * warning before 1.0 and fail the build from 1.0 when the major version is the baseline's.
 */
public final class PublishConventionPlugin implements Plugin<Project> {

    static final String PUBLICATION = "mavenJava";
    static final String STAGING_REPOSITORY = "centralStaging";
    static final String STAGING_DIR = "central-staging";
    private static final String URL = "https://resilience4cata.dev";
    private static final Pattern EXPORTS = Pattern.compile("(?m)^\\s*exports\\s+([\\w.]+)\\s*;");

    /** The {@code r4cApi} extension: which packages of a module are its API. */
    public abstract static class ApiExtension {

        /** Creates the extension. */
        public ApiExtension() {
        }

        /**
         * The API packages, exactly (a subpackage is listed on its own). Defaults to the unqualified
         * {@code exports} of {@code src/main/java/module-info.java}, or none.
         *
         * @return the packages
         */
        public abstract ListProperty<String> getPackages();
    }

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
        project.getPluginManager().withPlugin("java", plugin -> configureApiCompatibility(project));

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

    private static void configureApiCompatibility(Project project) {
        ProviderFactory providers = project.getProviders();
        ApiExtension api = project.getExtensions().create("r4cApi", ApiExtension.class);
        api.getPackages().convention(providers
                .fileContents(project.getLayout().getProjectDirectory().file("src/main/java/module-info.java"))
                .getAsText()
                .map(PublishConventionPlugin::exports)
                .orElse(List.of()));

        String baseline = providers.gradleProperty("r4c.apiBaseline").get();
        String current = project.getVersion().toString();
        String artifactId = JavaLibraryConventionPlugin.artifactName(project);
        String coordinates = project.getGroup() + ":" + artifactId + ":" + baseline;

        Configuration baselineJar = baselineConfiguration(project, "apiBaseline", coordinates, false);
        Configuration baselineClasspath = baselineConfiguration(project, "apiBaselineClasspath", coordinates, true);
        VersionCatalog libs = project.getExtensions().getByType(VersionCatalogsExtension.class).named("libs");
        Configuration japicmp = project.getConfigurations().create("japicmp", configuration -> {
            configuration.setCanBeConsumed(false);
            configuration.setDescription("japicmp, for apiCompatibility.");
        });
        project.getDependencies().add(japicmp.getName(), libs.findLibrary("japicmp").orElseThrow());

        var task = project.getTasks().register("apiCompatibility", ApiCompatibilityTask.class, compare -> {
            compare.setGroup("verification");
            compare.setDescription("Compares the module's API with its " + baseline + " release (japicmp).");
            compare.getBaselineJar().from(lenient(baselineJar));
            compare.getBaselineClasspath().from(lenient(baselineClasspath));
            compare.getCurrentJar().from(project.getTasks().named("jar"));
            compare.getCurrentClasspath().from(project.getConfigurations().named("compileClasspath"));
            compare.getJapicmpClasspath().from(japicmp);
            compare.getPackages().set(api.getPackages());
            compare.getBaselineVersion().set(baseline);
            compare.getCurrentVersion().set(current);
            compare.getArtifactId().set(artifactId);
            compare.getFailOnIncompatible().set(major(current) >= 1 && major(current) == major(baseline));
            compare.getReportDirectory().set(project.getLayout().getBuildDirectory().dir("reports/api-compatibility"));
            ListProperty<String> packages = api.getPackages();
            compare.onlyIf("the module exports API packages", spec -> !packages.get().isEmpty());
        });
        project.getTasks().named("check").configure(check -> check.dependsOn(task));
    }

    /** The baseline artifact (or it and its dependencies), resolved as a Java library's API. */
    private static Configuration baselineConfiguration(Project project, String name, String coordinates, boolean transitive) {
        ObjectFactory objects = project.getObjects();
        Configuration configuration = project.getConfigurations().create(name, c -> {
            c.setCanBeConsumed(false);
            c.setTransitive(transitive);
            c.setDescription("The " + coordinates + " release, for apiCompatibility.");
            c.attributes(attributes -> {
                attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, Usage.JAVA_API));
                attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.class, Category.LIBRARY));
                attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                        objects.named(LibraryElements.class, LibraryElements.JAR));
                attributes.attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.class, Bundling.EXTERNAL));
            });
        });
        project.getDependencies().add(name, coordinates);
        return configuration;
    }

    /** No baseline (a module new since the last release, or offline) is an empty collection, not a failure. */
    private static FileCollection lenient(Configuration configuration) {
        return configuration.getIncoming().artifactView(view -> view.setLenient(true)).getFiles();
    }

    static List<String> exports(String moduleInfo) {
        String code = moduleInfo.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
        Matcher matcher = EXPORTS.matcher(code);
        List<String> packages = new ArrayList<>();
        while (matcher.find()) {
            packages.add(matcher.group(1));
        }
        return packages;
    }

    private static int major(String version) {
        return Integer.parseInt(version.substring(0, version.indexOf('.')));
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
