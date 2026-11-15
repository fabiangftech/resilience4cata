package dev.resilience4cata.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Compares what the code offers now with a file of the repository's {@code contract} directory: what
 * {@code docs/en/reference/compatibility.md} promises to keep within a major version, one entry per line. An entry
 * that disappeared breaks the promise; an entry that was added is allowed and only needs the file updated. Either
 * way the test fails and writes the expected file to {@code build/contract/}, to copy over when the change is meant.
 *
 * <p>A copy of {@code examples}' {@code Contract}, which has the other copies; keep them in sync.
 */
final class Contract {

    private Contract() {
    }

    static void check(String name, Collection<String> current) throws IOException {
        Path committed = directory().resolve(name);
        SortedSet<String> expected = new TreeSet<>();
        if (Files.exists(committed)) {
            for (String line : Files.readAllLines(committed)) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    expected.add(line);
                }
            }
        }
        SortedSet<String> actual = new TreeSet<>(current);
        if (actual.equals(expected)) {
            return;
        }
        Path out = Path.of("build", "contract", name);
        Files.createDirectories(out.getParent());
        List<String> lines = new ArrayList<>();
        for (String line : Files.exists(committed) ? Files.readAllLines(committed) : List.<String>of()) {
            if (line.startsWith("#")) {
                lines.add(line); // keep the file's header
            }
        }
        lines.addAll(actual);
        Files.write(out, lines);
        SortedSet<String> removed = new TreeSet<>(expected);
        removed.removeAll(actual);
        SortedSet<String> added = new TreeSet<>(actual);
        added.removeAll(expected);
        StringBuilder message = new StringBuilder("contract/" + name + " differs from the code.");
        if (!removed.isEmpty()) {
            message.append("\nRemoved or changed: only a new major version may do this"
                    + " (docs/en/reference/compatibility.md):");
            removed.forEach(line -> message.append("\n  - ").append(line));
        }
        if (!added.isEmpty()) {
            message.append("\nAdded: allowed in a minor version.");
            added.forEach(line -> message.append("\n  + ").append(line));
        }
        message.append("\nIf the change is meant, copy ").append(out.toAbsolutePath()).append(" to ")
                .append(committed.toAbsolutePath()).append('.');
        throw new AssertionError(message);
    }

    /** The repository's {@code contract} directory, found from the test's working directory (its module). */
    private static Path directory() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("contract")) && Files.exists(dir.resolve("settings.gradle"))) {
                return dir.resolve("contract");
            }
        }
        throw new IllegalStateException("no contract directory above " + Path.of("").toAbsolutePath());
    }
}
