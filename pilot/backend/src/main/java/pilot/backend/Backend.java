package pilot.backend;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The dependency the services protect. Modes, switched by {@code POST /admin/mode/<mode>}:
 * <ul>
 *   <li>{@code normal}: 3 ms plus an exponential delay of mean 5 ms (at most 100 ms), and 1 % of answers are 503;
 *   <li>{@code down}: every answer is an immediate 503;
 *   <li>{@code slow}: every answer takes 1 s.
 * </ul>
 * {@code GET /admin/stats} returns the requests per mode and the most requests in flight.
 */
public final class Backend {

    private static volatile String mode = "normal";
    private static final AtomicInteger inFlight = new AtomicInteger();
    private static final AtomicInteger maxInFlight = new AtomicInteger();
    private static final AtomicLong requests = new AtomicLong();
    private static final AtomicLong errors = new AtomicLong();

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9000;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 8192);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/stock/", Backend::answer);
        server.createContext("/quote/", Backend::answer);
        server.createContext("/admin/mode/", exchange -> {
            mode = exchange.getRequestURI().getPath().substring("/admin/mode/".length());
            maxInFlight.set(inFlight.get());
            send(exchange, 200, mode);
        });
        server.createContext("/admin/stats", exchange -> send(exchange, 200,
                "{\"mode\":\"%s\",\"requests\":%d,\"errors\":%d,\"inFlight\":%d,\"maxInFlight\":%d}".formatted(
                        mode, requests.get(), errors.get(), inFlight.get(), maxInFlight.get())));
        server.start();
        System.out.println("backend on " + port);
    }

    private static void answer(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
        try {
            String current = mode;
            ThreadLocalRandom random = ThreadLocalRandom.current();
            switch (current) {
                case "down" -> {
                    errors.incrementAndGet();
                    send(exchange, 503, "down");
                    return;
                }
                case "slow" -> Thread.sleep(1000);
                default -> {
                    long micros = 3000 + Math.min(97_000, (long) (-Math.log(1 - random.nextDouble()) * 5000));
                    Thread.sleep(micros / 1000, (int) (micros % 1000) * 1000);
                    if (random.nextInt(100) == 0) {
                        errors.incrementAndGet();
                        send(exchange, 503, "unlucky");
                        return;
                    }
                }
            }
            send(exchange, 200, "ok");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            send(exchange, 500, "interrupted");
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
