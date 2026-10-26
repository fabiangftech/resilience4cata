package dev.resilience4cata.build;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 * The {@code quarkusExtension} extension of the runtime project, read by the Quarkus Gradle plugin (applied by
 * {@code quarkus:it}) to find the deployment module of a project dependency.
 *
 * <p>Without it the plugin looks for {@code META-INF/quarkus-extension.properties} in the project's source resource
 * directories, or else in its jar. It does so while the build is configured, before any task has run, so on a clean
 * build the jar does not exist yet: the deployment module is left out and the application is built without the
 * extension's build steps. This object answers from the configuration instead. The plugin calls the four getters by
 * reflection, by these names (the contract of Quarkus's own {@code io.quarkus.extension} plugin, which defines an
 * extension of the same name).
 */
public abstract class QuarkusExtensionModel {

    /** Creates the extension. */
    public QuarkusExtensionModel() {
    }

    /**
     * The deployment module, as a project path.
     *
     * @return the path, for example {@code :quarkus:deployment}
     */
    public abstract Property<String> getDeploymentModule();

    /**
     * Conditional dependencies ({@code group:artifact:version}); none here.
     *
     * @return the dependencies
     */
    public abstract ListProperty<String> getConditionalDependencies();

    /**
     * Conditional dependencies in dev mode; none here.
     *
     * @return the dependencies
     */
    public abstract ListProperty<String> getConditionalDevDependencies();

    /**
     * Conditions on other dependencies ({@code group:artifact}); none here.
     *
     * @return the conditions
     */
    public abstract ListProperty<String> getDependencyConditions();
}
