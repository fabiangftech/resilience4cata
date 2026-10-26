package dev.resilience4cata.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Writes a Quarkus extension's descriptors under {@code META-INF/} of the output directory:
 * {@code quarkus-extension.properties} with {@code deployment-artifact}, and {@code quarkus-extension.yaml}, the
 * template with {@code @artifact@}, {@code @quarkusCoreVersion@} and {@code @minimumJavaVersion@} replaced.
 */
@CacheableTask
public abstract class QuarkusExtensionDescriptorTask extends DefaultTask {

    private static final Pattern PLACEHOLDER = Pattern.compile("@([A-Za-z]+)@");

    /** The {@code quarkus-extension.yaml} template. */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getTemplate();

    /** The runtime artifact, {@code group:artifact::jar:version}. */
    @Input
    public abstract Property<String> getArtifact();

    /** The deployment artifact, {@code group:artifact:version}. */
    @Input
    public abstract Property<String> getDeploymentArtifact();

    /** The Quarkus core version the extension is built with. */
    @Input
    public abstract Property<String> getQuarkusCoreVersion();

    /** The minimum Java version. */
    @Input
    public abstract Property<String> getMinimumJavaVersion();

    /** Where {@code META-INF/} is written. */
    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    @TaskAction
    void write() throws IOException {
        Path metaInf = getOutputDirectory().get().getAsFile().toPath().resolve("META-INF");
        Files.createDirectories(metaInf);
        Files.writeString(metaInf.resolve("quarkus-extension.properties"),
                "deployment-artifact=" + getDeploymentArtifact().get() + "\n", StandardCharsets.ISO_8859_1);
        Map<String, String> values = Map.of(
                "artifact", getArtifact().get(),
                "quarkusCoreVersion", getQuarkusCoreVersion().get(),
                "minimumJavaVersion", getMinimumJavaVersion().get());
        String template = Files.readString(getTemplate().get().getAsFile().toPath(), StandardCharsets.UTF_8);
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder yaml = new StringBuilder();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            if (value == null) {
                throw new GradleException(getTemplate().get() + ": unknown placeholder " + matcher.group()
                        + "; known: " + values.keySet());
            }
            matcher.appendReplacement(yaml, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(yaml);
        Files.writeString(metaInf.resolve("quarkus-extension.yaml"), yaml, StandardCharsets.UTF_8);
    }
}
