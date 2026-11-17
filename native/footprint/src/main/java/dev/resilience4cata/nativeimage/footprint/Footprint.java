package dev.resilience4cata.nativeimage.footprint;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;
import java.util.stream.Stream;

/**
 * Starts native images of the shop applications, with and without resilience4cata, and measures each: the time from
 * starting the process to the first answered request, and the resident set size (RSS) right after that request and
 * after a fixed load. The runs of the images alternate, so that a slower period of the machine affects them all.
 *
 * <p>Arguments: the report file, the number of runs per image, then one {@code name=path} per image (the image, or the
 * directory that holds it). An image whose
 * name ends with {@code -plain} is the baseline of the image named without that suffix; it must not use generated
 * classes, the other must (checked on {@code /reactive/beans} at every run).
 */
public final class Footprint {

    /** What every request of the load asks, in turn: the same paths in both applications. */
    private static final List<String> LOAD = List.of(
            "/stock/abc", "/price/abc", "/reactive/quotes/a", "/reactive/rates/eur", "/reactive/rate-ticks/eur");
    private static final int LOAD_THREADS = 8;
    private static final int LOAD_REQUESTS_PER_THREAD = 250;
    private static final Duration STARTUP_LIMIT = Duration.ofSeconds(60);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();

    private Footprint() {
    }

    public static void main(String[] args) throws Exception {
        Path report = Path.of(args[0]);
        int runs = Integer.parseInt(args[1]);
        Map<String, Path> images = new LinkedHashMap<>();
        for (int i = 2; i < args.length; i++) {
            String[] image = args[i].split("=", 2);
            images.put(image[0], executableIn(Path.of(image[1])));
        }
        Map<String, List<Run>> results = new LinkedHashMap<>();
        images.keySet().forEach(name -> results.put(name, new ArrayList<>()));
        Path logs = report.resolveSibling("logs");
        Files.createDirectories(logs);
        Footprint footprint = new Footprint();
        for (int run = 1; run <= runs; run++) {
            for (Map.Entry<String, Path> image : images.entrySet()) {
                Run result = footprint.measure(image.getKey(), image.getValue(), logs.resolve(image.getKey() + ".log"));
                System.out.printf(Locale.ROOT, "run %d %s: startup %d ms, RSS %.1f MB, after load %.1f MB%n", run,
                        image.getKey(), result.startupMillis(), result.rssAfterStartMb(), result.rssAfterLoadMb());
                results.get(image.getKey()).add(result);
            }
        }
        String text = report(images, results, runs);
        Files.writeString(report, text);
        System.out.print(text);
    }

    /**
     * The one executable file of a {@code nativeCompile} directory, wherever its name comes from (Spring Boot's plugin
     * names the image after the jar, version included), next to the shared libraries GraalVM copies there; a file is
     * accepted as it is.
     */
    static Path executableIn(Path path) throws IOException {
        if (!Files.isDirectory(path)) {
            return path;
        }
        try (Stream<Path> files = Files.list(path)) {
            List<Path> executables = files.filter(Files::isRegularFile).filter(Files::isExecutable)
                    .filter(file -> !file.getFileName().toString().matches(".*\\.(so|dylib|dll|jar|json|txt)$"))
                    .toList();
            if (executables.size() != 1) {
                throw new IllegalStateException("Expected one native image in " + path + ", found " + executables);
            }
            return executables.getFirst();
        }
    }

    private Run measure(String name, Path image, Path log) throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        ProcessBuilder builder = new ProcessBuilder(image.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        // Each framework reads its own variable for the port.
        builder.environment().put("SERVER_PORT", Integer.toString(port));
        builder.environment().put("MICRONAUT_SERVER_PORT", Integer.toString(port));
        long start = System.nanoTime();
        Process process = builder.start();
        try {
            long startupMillis = waitUntilAnswered(process, uri(port, LOAD.getFirst()), start, log);
            double rssAfterStart = rssMb(process);
            checkBeans(name, port);
            load(port);
            double rssAfterLoad = rssMb(process);
            return new Run(startupMillis, rssAfterStart, rssAfterLoad);
        } finally {
            process.destroy();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor();
            }
        }
    }

    private long waitUntilAnswered(Process process, URI uri, long start, Path log) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).build();
        while (true) {
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                long millis = Duration.ofNanos(System.nanoTime() - start).toMillis();
                if (response.statusCode() != 200) {
                    throw new IllegalStateException(uri + " answered " + response.statusCode() + ": "
                            + response.body());
                }
                return millis;
            } catch (ConnectException | HttpTimeoutException notYet) {
                if (!process.isAlive()) {
                    throw new IllegalStateException("The image exited with " + process.exitValue() + " before "
                            + "answering; its output:\n" + Files.readString(log, StandardCharsets.UTF_8));
                }
                if (System.nanoTime() - start > STARTUP_LIMIT.toNanos()) {
                    throw new IllegalStateException("No answer from " + uri + " within " + STARTUP_LIMIT);
                }
                Thread.sleep(2);
            }
        }
    }

    private void checkBeans(String name, int port) throws IOException, InterruptedException {
        String beans = http.send(HttpRequest.newBuilder(uri(port, "/reactive/beans")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        boolean generated = beans.contains("$Resilient");
        if (generated == name.endsWith("-plain")) {
            throw new IllegalStateException(name + " has the beans " + beans + ": the baseline must not use "
                    + "generated classes, the application with resilience4cata must");
        }
    }

    private void load(int port) throws Exception {
        try (ExecutorService threads = Executors.newFixedThreadPool(LOAD_THREADS)) {
            List<Future<?>> done = new ArrayList<>();
            for (int t = 0; t < LOAD_THREADS; t++) {
                int thread = t;
                done.add(threads.submit(() -> {
                    for (int i = 0; i < LOAD_REQUESTS_PER_THREAD; i++) {
                        URI uri = uri(port, LOAD.get((thread + i) % LOAD.size()));
                        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri).build(),
                                HttpResponse.BodyHandlers.ofString());
                        if (response.statusCode() != 200) {
                            throw new IllegalStateException(uri + " answered " + response.statusCode());
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> future : done) {
                future.get();
            }
        }
    }

    /** The process's resident set size, from {@code ps} (in KB on Linux and macOS). */
    private static double rssMb(Process process) throws IOException, InterruptedException {
        Process ps = new ProcessBuilder("ps", "-o", "rss=", "-p", Long.toString(process.pid()))
                .redirectErrorStream(true).start();
        String out = new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (ps.waitFor() != 0 || out.isEmpty()) {
            throw new IllegalStateException("ps found no process " + process.pid() + ": " + out);
        }
        return Long.parseLong(out) / 1024.0;
    }

    private static URI uri(int port, String path) {
        return URI.create("http://localhost:" + port + path);
    }

    static String report(Map<String, Path> images, Map<String, List<Run>> results, int runs) throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("# Native image footprint\n\n");
        out.append(String.format(Locale.ROOT, "%d runs per image, alternating; medians, with the range in brackets. "
                + "Startup: from starting the process to the first answered `GET /stock/abc`. RSS: right after "
                + "that request, and after %d requests from %d threads.%n%n", runs,
                LOAD_THREADS * LOAD_REQUESTS_PER_THREAD, LOAD_THREADS));
        out.append("| Image | Size (MB) | Startup (ms) | RSS after start (MB) | RSS after load (MB) |\n");
        out.append("|---|---:|---:|---:|---:|\n");
        for (Map.Entry<String, List<Run>> entry : results.entrySet()) {
            List<Run> list = entry.getValue();
            out.append(String.format(Locale.ROOT, "| %s | %.1f | %s | %s | %s |%n", entry.getKey(),
                    Files.size(images.get(entry.getKey())) / (1024.0 * 1024.0),
                    summary(list.stream().mapToDouble(Run::startupMillis).toArray(), "%.0f"),
                    summary(list.stream().mapToDouble(Run::rssAfterStartMb).toArray(), "%.1f"),
                    summary(list.stream().mapToDouble(Run::rssAfterLoadMb).toArray(), "%.1f")));
        }
        out.append("\n## What resilience4cata adds\n\n");
        out.append("Difference of the medians, application with the library minus its baseline.\n\n");
        out.append("| Application | Size (MB) | Startup (ms) | RSS after start (MB) | RSS after load (MB) |\n");
        out.append("|---|---:|---:|---:|---:|\n");
        for (String name : results.keySet()) {
            String baseline = name + "-plain";
            if (!results.containsKey(baseline)) {
                continue;
            }
            List<Run> with = results.get(name);
            List<Run> without = results.get(baseline);
            out.append(String.format(Locale.ROOT, "| %s | %+.1f | %+.0f | %+.1f | %+.1f |%n", name,
                    (Files.size(images.get(name)) - Files.size(images.get(baseline))) / (1024.0 * 1024.0),
                    median(with, Run::startupMillis) - median(without, Run::startupMillis),
                    median(with, Run::rssAfterStartMb) - median(without, Run::rssAfterStartMb),
                    median(with, Run::rssAfterLoadMb) - median(without, Run::rssAfterLoadMb)));
        }
        return out.toString();
    }

    private static double median(List<Run> runs, ToDoubleFunction<Run> value) {
        return median(runs.stream().mapToDouble(value).toArray());
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int middle = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
    }

    private static String summary(double[] values, String format) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return String.format(Locale.ROOT, format + " [" + format + "–" + format + "]", median(values), sorted[0],
                sorted[sorted.length - 1]);
    }

    record Run(long startupMillis, double rssAfterStartMb, double rssAfterLoadMb) {
    }
}
