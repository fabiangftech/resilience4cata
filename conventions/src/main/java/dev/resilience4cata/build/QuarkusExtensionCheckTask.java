package dev.resilience4cata.build;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

/**
 * Fails when a Quarkus extension on the deployment module's runtime class path (which includes the runtime
 * module's) names a {@code deployment-artifact} that is not on that class path: every runtime extension the
 * extension depends on needs its deployment counterpart on the deployment module.
 */
@CacheableTask
public abstract class QuarkusExtensionCheckTask extends DefaultTask {

    /** The deployment module's runtime class path. */
    @Classpath
    public abstract ConfigurableFileCollection getClasspath();

    /** {@code group:artifact} of every external module on that class path. */
    @Input
    public abstract SetProperty<String> getComponents();

    /** {@code group:artifact} of the deployment module itself, named by its own runtime module. */
    @Input
    public abstract org.gradle.api.provider.Property<String> getSelf();

    /** Touched when the check passes, so the task is up to date afterwards. */
    @OutputFile
    public abstract RegularFileProperty getMarker();

    /** {@code group:artifact} of every module in a resolution result; projects by their {@code group:name}. */
    static Set<String> modules(ResolvedComponentResult root) {
        Set<String> modules = new TreeSet<>();
        Set<ResolvedComponentResult> seen = new HashSet<>();
        List<ResolvedComponentResult> pending = new ArrayList<>(List.of(root));
        while (!pending.isEmpty()) {
            ResolvedComponentResult component = pending.removeLast();
            if (!seen.add(component)) {
                continue;
            }
            if (component.getId() instanceof ModuleComponentIdentifier module) {
                modules.add(module.getGroup() + ":" + module.getModule());
            } else if (component.getId() instanceof ProjectComponentIdentifier && component.getModuleVersion() != null) {
                modules.add(component.getModuleVersion().getGroup() + ":" + component.getModuleVersion().getName());
            }
            for (DependencyResult dependency : component.getDependencies()) {
                if (dependency instanceof ResolvedDependencyResult resolved) {
                    pending.add(resolved.getSelected());
                }
            }
        }
        return modules;
    }

    @TaskAction
    void check() throws IOException {
        Set<String> present = getComponents().get();
        Set<String> missing = new TreeSet<>();
        for (File file : getClasspath()) {
            String deployment = deploymentArtifact(file);
            if (deployment == null) {
                continue;
            }
            String[] coordinates = deployment.split(":");
            String key = coordinates[0] + ":" + coordinates[1];
            // This extension's own runtime module points at the project being built.
            if (!present.contains(key) && !key.equals(getSelf().get())) {
                missing.add(key + " (for " + file.getName() + ")");
            }
        }
        if (!missing.isEmpty()) {
            throw new GradleException("The deployment module must depend on the deployment artifact of every"
                    + " extension the runtime module depends on; missing: " + String.join(", ", missing));
        }
        Files.writeString(getMarker().get().getAsFile().toPath(), "ok\n", StandardCharsets.UTF_8);
    }

    private static String deploymentArtifact(File file) throws IOException {
        if (!file.isFile() || !file.getName().endsWith(".jar")) {
            return null;
        }
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry entry = zip.getEntry("META-INF/quarkus-extension.properties");
            if (entry == null) {
                return null;
            }
            Properties properties = new Properties();
            try (InputStream in = zip.getInputStream(entry)) {
                properties.load(in);
            }
            return properties.getProperty("deployment-artifact");
        }
    }
}
