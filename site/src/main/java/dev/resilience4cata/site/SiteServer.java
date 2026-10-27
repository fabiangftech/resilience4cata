package dev.resilience4cata.site;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Serves the generated site locally with GitHub Pages' URL rules: a file as is, a folder's {@code index.html} (a
 * folder without its trailing slash is redirected), {@code x.html} for {@code /x}, else {@code 404.html} with status
 * 404.
 *
 * <p>Usage: {@code SiteServer <site dir> [port]}; {@code ./gradlew :site:serve}.
 */
public final class SiteServer {

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("md", "text/markdown; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("xml", "application/xml; charset=utf-8"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("json", "application/json"));

    private SiteServer() {
    }

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 3000;
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                serve(exchange, root);
            }
        });
        server.start();
        System.out.println("Serving " + root + " on http://localhost:" + port + "/ (Ctrl+C to stop)");
    }

    private static void serve(HttpExchange exchange, Path root) throws IOException {
        String path = URI.create(exchange.getRequestURI().toString()).getPath();
        Path file = root.resolve(path.substring(1)).normalize();
        if (!file.startsWith(root)) {
            send(exchange, 404, root.resolve("404.html"));
            return;
        }
        if (Files.isDirectory(file)) {
            if (!path.endsWith("/")) {
                exchange.getResponseHeaders().set("Location", path + "/");
                exchange.sendResponseHeaders(301, -1);
                return;
            }
            file = file.resolve("index.html");
        } else if (!Files.exists(file)) {
            file = file.resolveSibling(file.getFileName() + ".html");
        }
        if (Files.isRegularFile(file)) {
            send(exchange, 200, file);
        } else {
            send(exchange, 404, root.resolve("404.html"));
        }
    }

    private static void send(HttpExchange exchange, int status, Path file) throws IOException {
        String name = file.getFileName().toString();
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        byte[] body = Files.readAllBytes(file);
        exchange.getResponseHeaders().set("Content-Type", TYPES.getOrDefault(extension, "application/octet-stream"));
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
