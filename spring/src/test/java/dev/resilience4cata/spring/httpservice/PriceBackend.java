package dev.resilience4cata.spring.httpservice;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The prices server: answers each request with the next scripted {@link Answer} (the last one repeats), counting the
 * requests. Runs on the JDK's HTTP server with a virtual thread per request, so a slow answer blocks no other.
 */
final class PriceBackend implements AutoCloseable {

    /** A status and body, sent after a delay. */
    record Answer(int status, String body, Duration delay) {
        static Answer of(int status, String body) {
            return new Answer(status, body, Duration.ZERO);
        }
    }

    private final HttpServer server;
    private final ConcurrentLinkedQueue<Answer> script = new ConcurrentLinkedQueue<>();
    private volatile Answer last = Answer.of(200, "42");
    private final AtomicInteger requests = new AtomicInteger();

    PriceBackend() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/prices/", exchange -> {
            requests.incrementAndGet();
            Answer answer = script.poll();
            if (answer == null) {
                answer = last;
            } else if (script.isEmpty()) {
                last = answer;
            }
            try {
                Thread.sleep(answer.delay());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(answer.status(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    /** The answers of the next requests, in order; the last one repeats. */
    PriceBackend answer(Answer... answers) {
        script.addAll(List.of(answers));
        return this;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    int requests() {
        return requests.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
