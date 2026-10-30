package dev.resilience4cata.spring.scopes;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Request- and session-scoped beans of a Spring MVC application on Tomcat, called over HTTP. An annotated class in
 * the request scope gets one generated-subclass instance per request, and all the attempts of a call run on it; a
 * bean implementing an annotated interface in the request or session scope is reached through one wrapper around
 * its scoped proxy, so each attempt runs on the current request's or session's instance. Named policies are shared
 * by every instance, as for prototypes.
 */
@SpringBootTest(classes = WebScopesTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.main.web-application-type=servlet")
class WebScopesTest {

    @SpringBootApplication(scanBasePackageClasses = WebScopesTest.class)
    static class App {
    }

    @LocalServerPort
    int port;

    @Test
    void aRequestScopedAnnotatedClassRetriesOnTheRequestsOwnInstance() throws Exception {
        HttpClient http = client();
        String first = get(http, "/scopes/quotes/a?failures=2", 200);
        String[] parts = first.split(" \\| ");
        String[] results = parts[0].split(" ");
        String instance = results[0].substring(0, results[0].indexOf(':'));
        assertThat(results).containsExactly(instance + ":a", instance + ":a");
        assertThat(parts[1].split(" ")).as("two failed attempts, then the two calls, all on one instance")
                .containsExactly(instance, instance, instance, instance);

        String second = get(http, "/scopes/quotes/a", 200);
        assertThat(second).as("a new request, a new instance").doesNotContain(instance + ":");
    }

    @Test
    void aRequestScopedAnnotatedClassThatKeepsFailingGivesUp() throws Exception {
        assertThat(get(client(), "/scopes/quotes/a?failures=10", 502)).isEqualTo("down");
    }

    @Test
    void everyRequestsInstanceSharesTheNamedBreaker() throws Exception {
        HttpClient http = client();
        get(http, "/scopes/guarded-quotes/a?failures=1", 502);
        get(http, "/scopes/guarded-quotes/a?failures=1", 502);
        assertThat(get(http, "/scopes/guarded-quotes/a", 503))
                .as("opened by two other requests' instances").isEqualTo("open");
    }

    @Test
    void aRequestScopedImplementationOfAnAnnotatedInterfaceIsRetriedThroughOneWrapper() throws Exception {
        HttpClient http = client();
        String[] parts = get(http, "/scopes/trace?failures=2", 200).split(" \\| ");
        String instance = parts[0].split(" ")[0];
        assertThat(parts[0]).isEqualTo(instance + " " + instance);
        assertThat(parts[1].split(" ")).containsExactly(instance, instance, instance, instance);

        assertThat(get(http, "/scopes/trace", 200)).doesNotStartWith(instance + " ");
    }

    @Test
    void aSessionScopedImplementationOfAnAnnotatedInterfaceKeepsItsStatePerSession() throws Exception {
        HttpClient alice = client();
        HttpClient bob = client();

        String added = get(alice, "/scopes/cart/add/apple?failures=2", 200);
        String cart = added.split(" \\| ")[1].split(" ")[0];
        assertThat(added).isEqualTo("1 | " + cart + " " + cart + " " + cart);
        assertThat(get(alice, "/scopes/cart/add/pear", 200)).isEqualTo("2 | " + cart);
        assertThat(get(alice, "/scopes/cart", 200)).as("the same session's cart, a later request")
                .isEqualTo("apple,pear");

        assertThat(get(bob, "/scopes/cart/add/plum", 200)).as("another session, another cart")
                .startsWith("1 | ").doesNotContain(cart);
        assertThat(get(bob, "/scopes/cart", 200)).isEqualTo("plum");
    }

    /** A client with its own cookie store: its own HTTP session. */
    private static HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    }

    private String get(HttpClient http, String path, int status) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path + ": " + response.body()).isEqualTo(status);
        return response.body();
    }
}
