package pilot.common;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** The services' call to the simulated backend: a blocking JDK HTTP call, interruptible, failing on any non-200. */
public final class BackendClient {

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(1))
            .build();
    private final String base = System.getProperty("backend.url", "http://127.0.0.1:9000");

    public String get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new BackendException(response.statusCode());
        }
        return response.body();
    }

    /** A non-200 answer from the backend. */
    public static final class BackendException extends IOException {
        public BackendException(int status) {
            super("backend answered " + status);
        }
    }
}
