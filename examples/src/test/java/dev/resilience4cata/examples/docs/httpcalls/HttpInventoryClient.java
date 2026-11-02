package dev.resilience4cata.examples.docs.httpcalls;

// The implementation of docs/{en,es}/guides/http-calls.md.
// tag::http-calls-implementation[]
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public final class HttpInventoryClient implements InventoryClient {
    private final HttpClient http;
    private final URI base;

    public HttpInventoryClient(HttpClient http, URI base) {
        this.http = http;
        this.base = base;
    }

    @Override
    public int stock(String sku) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(base.resolve("/stock/" + sku)).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status >= 500 || status == 429) {
            throw new ServerError(status);
        }
        if (status >= 400) {
            throw new ClientError(status);
        }
        return Integer.parseInt(response.body().trim());
    }
}
// end::http-calls-implementation[]
