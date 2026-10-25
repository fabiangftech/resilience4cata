package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps the documentation site ({@code docs/}) honest.
 *
 * <p>A code block on a page is preceded by {@code <!-- snippet: id -->} and must be identical to the region
 * {@code tag::id[]} … {@code end::id[]} of a file under {@code examples/src/test} (a marker is a line
 * starting with {@code //}, {@code #} or {@code <!--}; marker lines are dropped and the region is dedented), so every snippet on the site compiles and runs. In the
 * user pages ({@link #CHECKED_DIRECTORIES}) every {@code java} block needs such a marker, or
 * {@code <!-- not-compiled: reason -->} when it cannot compile on purpose (a wrong example, generated code).
 * The English and Spanish trees have the same files, and each page pair uses the same snippets in order.
 */
class DocsTest {

    private static final Path DOCS = Path.of("..", "docs");
    private static final Path SOURCES = Path.of("src", "test");
    private static final List<String> LANGUAGES = List.of("en", "es");
    /** Pages whose {@code java} blocks must all be compiled; the landing page is {@code README.md}. */
    private static final List<String> CHECKED_DIRECTORIES = List.of("start", "policies", "guides", "observability", "testing", "migrate");

    private static final Pattern MARKER = Pattern.compile("^<!-- (snippet|not-compiled):\\s*(.+?)\\s*-->$");
    private static final Pattern FENCE = Pattern.compile("^(`{3,}|~{3,})(\\w*).*$");
    private static final Pattern REGION = Pattern.compile("^\\s*(?://|#|<!--)\\s*(tag|end)::([\\w.-]+)\\[]");

    @Test
    void theEnglishAndSpanishTreesHaveTheSameFiles() {
        assertThat(relativeFiles("es")).isEqualTo(relativeFiles("en"));
    }

    @Test
    void everySnippetMatchesItsRegionInTheExamples() {
        Map<String, String> regions = regions();
        List<String> problems = new ArrayList<>();
        for (String language : LANGUAGES) {
            for (Path page : pages(language)) {
                for (Block block : blocks(page)) {
                    if (!block.kind().equals("snippet")) {
                        continue;
                    }
                    String expected = regions.get(block.id());
                    if (expected == null) {
                        problems.add(block.where() + ": no region tag::" + block.id() + "[] under examples/src/test");
                    } else if (!expected.equals(block.code())) {
                        problems.add(block.where() + ": snippet '" + block.id() + "' differs from its region:\n--- page\n"
                                + block.code() + "\n--- examples\n" + expected);
                    }
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void everyJavaBlockInTheUserPagesIsCompiledOrSaysWhyNot() {
        List<String> problems = new ArrayList<>();
        for (String language : LANGUAGES) {
            for (Path page : pages(language)) {
                String relative = DOCS.resolve(language).relativize(page).toString().replace('\\', '/');
                boolean checked = CHECKED_DIRECTORIES.stream().anyMatch(d -> relative.startsWith(d + "/"));
                if (!checked) {
                    continue;
                }
                for (Block block : blocks(page)) {
                    if (block.language().equals("java") && block.kind().isEmpty()) {
                        problems.add(block.where() + ": java block without <!-- snippet: id --> or <!-- not-compiled: reason -->");
                    }
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void eachPagePairUsesTheSameSnippetsInTheSameOrder() {
        List<String> problems = new ArrayList<>();
        for (Path english : pages("en")) {
            Path spanish = DOCS.resolve("es").resolve(DOCS.resolve("en").relativize(english));
            if (!Files.exists(spanish)) {
                continue; // reported by theEnglishAndSpanishTreesHaveTheSameFiles
            }
            List<String> en = blocks(english).stream().filter(b -> b.kind().equals("snippet")).map(Block::id).toList();
            List<String> es = blocks(spanish).stream().filter(b -> b.kind().equals("snippet")).map(Block::id).toList();
            if (!en.equals(es)) {
                problems.add(english + " uses " + en + " but " + spanish + " uses " + es);
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void everyRegionIdIsDefinedOnce() {
        regions(); // throws on duplicates and unclosed regions
    }

    private record Block(String where, String kind, String id, String language, String code) {
    }

    /** The fenced blocks of a page, each with the marker on the closest non-blank line above it (if any). */
    static List<Block> blocks(Path page) {
        List<String> lines = read(page);
        List<Block> blocks = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher open = FENCE.matcher(lines.get(i).strip());
            if (!open.matches()) {
                continue;
            }
            String fence = open.group(1);
            int indent = leadingSpaces(lines.get(i));
            int start = i;
            List<String> code = new ArrayList<>();
            int j = i + 1;
            while (j < lines.size() && !lines.get(j).strip().startsWith(fence)) {
                String line = lines.get(j);
                code.add(line.length() >= indent && line.substring(0, indent).isBlank() ? line.substring(indent) : line.strip());
                j++;
            }
            String kind = "";
            String id = "";
            int above = start - 1;
            while (above >= 0 && lines.get(above).isBlank()) {
                above--;
            }
            if (above >= 0) {
                Matcher marker = MARKER.matcher(lines.get(above).strip());
                if (marker.matches()) {
                    kind = marker.group(1);
                    id = marker.group(2);
                }
            }
            blocks.add(new Block(page + ":" + (start + 1), kind, id, open.group(2), String.join("\n", code)));
            i = j;
        }
        return blocks;
    }

    /** Every {@code tag::id[]} region under {@code examples/src/test}, dedented, without marker lines. */
    static Map<String, String> regions() {
        Map<String, String> regions = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                List<String> lines = read(file);
                Map<String, List<String>> open = new LinkedHashMap<>();
                for (int n = 0; n < lines.size(); n++) {
                    String line = lines.get(n);
                    Matcher marker = REGION.matcher(line);
                    if (marker.find()) {
                        String id = marker.group(2);
                        if (marker.group(1).equals("tag")) {
                            if (regions.containsKey(id) || open.containsKey(id)) {
                                throw new AssertionError("region '" + id + "' defined twice (" + file + ":" + (n + 1) + ")");
                            }
                            open.put(id, new ArrayList<>());
                        } else {
                            List<String> body = open.remove(id);
                            if (body == null) {
                                throw new AssertionError("end::" + id + "[] without tag (" + file + ":" + (n + 1) + ")");
                            }
                            regions.put(id, dedent(body));
                        }
                        continue;
                    }
                    for (List<String> body : open.values()) {
                        body.add(line);
                    }
                }
                if (!open.isEmpty()) {
                    throw new AssertionError("unclosed regions " + open.keySet() + " in " + file);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return regions;
    }

    private static String dedent(List<String> lines) {
        int indent = lines.stream().filter(l -> !l.isBlank()).mapToInt(DocsTest::leadingSpaces).min().orElse(0);
        List<String> out = lines.stream().map(l -> l.isBlank() ? "" : l.substring(indent).stripTrailing()).toList();
        return String.join("\n", out);
    }

    private static int leadingSpaces(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') {
            n++;
        }
        return n;
    }

    private static List<Path> pages(String language) {
        try (Stream<Path> files = Files.walk(DOCS.resolve(language))) {
            return files.filter(p -> p.toString().endsWith(".md")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static TreeSet<String> relativeFiles(String language) {
        Path root = DOCS.resolve(language);
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> read(Path file) {
        try {
            return Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException(file.toString(), e);
        }
    }
}
