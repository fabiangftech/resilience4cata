package dev.resilience4cata.build;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/**
 * Uploads the Central bundle with the Central Portal Publisher API
 * ({@code POST /api/v1/publisher/upload}, multipart field {@code bundle}).
 *
 * <p>The deployment is {@code USER_MANAGED}: after Central validates it, it waits in the portal
 * (central.sonatype.com, Deployments) until someone presses Publish or Drop. Nothing is released by
 * this task. Credentials are a Central Portal user token, from the Gradle properties
 * {@code centralPortalUsername} and {@code centralPortalPassword} (or
 * {@code ORG_GRADLE_PROJECT_centralPortalUsername}/{@code ..Password}); the task fails without them.
 * No other task depends on it, so {@code build} never uploads anything.
 */
@DisableCachingByDefault(because = "Uploads to a remote service")
public abstract class CentralUploadTask extends DefaultTask {

    /** The bundle built by {@code centralBundle}. */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getBundle();

    /** The deployment name shown in the portal. */
    @Input
    public abstract Property<String> getDeploymentName();

    /** The Publisher API base URL ({@code https://central.sonatype.com}). */
    @Input
    public abstract Property<String> getPortalUrl();

    /** User token name. */
    @Internal
    public abstract Property<String> getUsername();

    /** User token password. */
    @Internal
    public abstract Property<String> getPassword();

    public CentralUploadTask() {
        getOutputs().upToDateWhen(task -> false);
    }

    @TaskAction
    public void upload() throws IOException, InterruptedException {
        if (!getUsername().isPresent() || !getPassword().isPresent()) {
            throw new GradleException("centralUpload needs a Central Portal user token: set the Gradle properties"
                    + " centralPortalUsername and centralPortalPassword (or ORG_GRADLE_PROJECT_centralPortalUsername"
                    + " and ORG_GRADLE_PROJECT_centralPortalPassword). Generate one at central.sonatype.com > View Account.");
        }
        Path bundle = getBundle().get().getAsFile().toPath();
        String token = Base64.getEncoder().encodeToString(
                (getUsername().get() + ":" + getPassword().get()).getBytes(StandardCharsets.UTF_8));
        String boundary = "r4c-" + UUID.randomUUID();
        byte[] head = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"bundle\"; filename=\"" + bundle.getFileName() + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        URI uri = URI.create(getPortalUrl().get() + "/api/v1/publisher/upload?publishingType=USER_MANAGED&name="
                + URLEncoder.encode(getDeploymentName().get(), StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(10))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.concat(
                        HttpRequest.BodyPublishers.ofByteArrays(List.of(head)),
                        HttpRequest.BodyPublishers.ofFile(bundle),
                        HttpRequest.BodyPublishers.ofByteArrays(List.of(tail))))
                .build();
        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()) {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        }
        if (response.statusCode() / 100 != 2) {
            throw new GradleException("Central Portal rejected the upload (HTTP " + response.statusCode() + "): " + response.body());
        }
        getLogger().lifecycle("Uploaded {} as deployment {}. Check it and press Publish at {}/publishing/deployments",
                bundle.getFileName(), response.body().strip(), getPortalUrl().get());
    }
}
