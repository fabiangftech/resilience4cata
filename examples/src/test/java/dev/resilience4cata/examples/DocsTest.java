package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
 * {@code tag::id[]} … {@code end::id[]} of a file under {@code examples/src/test}, or under {@code spring/src/test},
 * {@code micronaut/src/test} or {@code quarkus/it/src} for the Spring Boot, Micronaut and Quarkus guides, whose code
 * needs the framework and runs in that module's own tests (a marker is a line
 * starting with {@code //}, {@code #} or {@code <!--}; marker lines are dropped and the region is dedented), so every snippet on the site compiles and runs. In the
 * user pages ({@link #CHECKED_DIRECTORIES}) every {@code java} block needs such a marker, or
 * {@code <!-- not-compiled: reason -->} when it cannot compile on purpose (a wrong example, generated code).
 * Every language ({@link #LANGUAGES}) has the English tree's files, each translated page uses the English page's
 * snippets in order, and in the Portuguese and Chinese pages every heading carries the English page's heading id
 * ({@code :id=}), so links and anchors written in English work in every language.
 */
class DocsTest {

    private static final Path DOCS = Path.of("..", "docs");
    /** Where the regions are: these examples, and the Spring, Micronaut and Quarkus modules' tests (their guides' code). */
    private static final List<Path> SOURCES = List.of(Path.of("src", "test"), Path.of("..", "spring", "src", "test"),
            Path.of("..", "micronaut", "src", "test"), Path.of("..", "quarkus", "it", "src"));
    private static final List<String> LANGUAGES = List.of("en", "es", "pt", "zh");
    /** Translated with the English heading ids (Spanish pages have their own, older anchors). */
    private static final List<String> ENGLISH_IDS = List.of("pt", "zh");
    /** Pages whose {@code java} blocks must all be compiled; the landing page is {@code README.md}. */
    private static final List<String> CHECKED_DIRECTORIES = List.of("start", "policies", "guides", "observability", "testing", "migrate", "blog");

    private static final Pattern MARKER = Pattern.compile("^<!-- (snippet|not-compiled):(.+)-->$");
    private static final Pattern FENCE = Pattern.compile("^(`{3,}+|~{3,}+)(\\w*+).*$");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)(?:\\s+:id=([\\w-]+))?\\s*$");
    private static final Pattern REGION = Pattern.compile("^\\s*(?://|#|<!--)\\s*(tag|end)::([\\w.-]+)\\[]");

    @Test
    void everyLanguageHasTheEnglishFiles() {
        for (String language : LANGUAGES) {
            assertThat(relativeFiles(language)).as(language).isEqualTo(relativeFiles("en"));
        }
    }

    @Test
    void translatedHeadingsCarryTheEnglishIds() {
        List<String> problems = new ArrayList<>();
        for (Path english : pages("en")) {
            List<String> ids = headingIds(english);
            for (String language : ENGLISH_IDS) {
                Path translated = DOCS.resolve(language).resolve(DOCS.resolve("en").relativize(english));
                if (Files.exists(translated) && !headingIds(translated).equals(ids)) {
                    problems.add(translated + ": heading ids " + headingIds(translated) + ", English " + ids);
                }
            }
        }
        assertThat(problems).isEmpty();
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
                        problems.add(block.where() + ": no region tag::" + block.id()
                                + "[] under examples/src/test, spring/src/test, micronaut/src/test or quarkus/it/src");
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
    void everyTranslationUsesTheSameSnippetsInTheSameOrder() {
        List<String> problems = new ArrayList<>();
        for (Path english : pages("en")) {
            List<String> en = blocks(english).stream().filter(b -> b.kind().equals("snippet")).map(Block::id).toList();
            for (String language : LANGUAGES.subList(1, LANGUAGES.size())) {
                Path translated = DOCS.resolve(language).resolve(DOCS.resolve("en").relativize(english));
                if (!Files.exists(translated)) {
                    continue; // reported by everyLanguageHasTheEnglishFiles
                }
                List<String> other = blocks(translated).stream().filter(b -> b.kind().equals("snippet")).map(Block::id).toList();
                if (!en.equals(other)) {
                    problems.add(english + " uses " + en + " but " + translated + " uses " + other);
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void everyRegionIdIsDefinedOnce() {
        assertThatCode(DocsTest::regions).as("no duplicate or unclosed region").doesNotThrowAnyException();
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
                    id = marker.group(2).strip();
                }
            }
            blocks.add(new Block(page + ":" + (start + 1), kind, id, open.group(2), String.join("\n", code)));
            i = j;
        }
        return blocks;
    }

    /**
     * The id of every heading outside code blocks, in order: its {@code :id=}, or for an English heading without one
     * the slug Docsify gives it (as {@code SiteGenerator.slug} and its numbering of repeated slugs); the page title
     * ({@code #}) is counted but not listed. Tab labels
     * inside {@code <!-- tabs:start -->} are not headings.
     */
    static List<String> headingIds(Path page) {
        List<String> ids = new ArrayList<>();
        Map<String, Integer> used = new java.util.HashMap<>();
        boolean tabs = false;
        String fence = null;
        for (String line : read(page)) {
            String stripped = line.strip();
            if (fence != null) {
                if (stripped.startsWith(fence)) {
                    fence = null;
                }
                continue;
            }
            Matcher open = FENCE.matcher(stripped);
            if (open.matches()) {
                fence = open.group(1);
                continue;
            }
            if (stripped.startsWith("<!-- tabs:start")) {
                tabs = true;
            } else if (stripped.startsWith("<!-- tabs:end")) {
                tabs = false;
            }
            Matcher heading = HEADING.matcher(line);
            if (!heading.matches() || tabs && heading.group(2).startsWith("**")) {
                continue;
            }
            String id = heading.group(3);
            if (id == null) {
                // Docsify numbers a repeated slug (x, x-1, x-2); explicit ids do not count.
                String slug = slug(heading.group(2));
                Integer count = used.get(slug);
                used.put(slug, count == null ? 1 : count + 1);
                id = count == null ? slug : slug + "-" + count;
            }
            if (heading.group(1).length() > 1) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** Docsify's slug of a heading's text (inline code, emphasis and links reduced to their text). */
    static String slug(String text) {
        String plain = text.replaceAll("\\[([^\\]]*)]\\([^)]*\\)", "$1").replaceAll("<[^>]+>", "").replace("`", "")
                .replace("**", "").replace("__", "");
        String slug = plain.strip().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[\\u2000-\\u206F\\u2E00-\\u2E7F\\\\'!\"#$%&()*+,./:;<=>?@\\[\\]^`{|}~]", "")
                .replaceAll("\\s", "-");
        return !slug.isEmpty() && Character.isDigit(slug.charAt(0)) ? "_" + slug : slug;
    }

    /** Every {@code tag::id[]} region under {@link #SOURCES}, dedented, without marker lines. */
    static Map<String, String> regions() {
        Map<String, String> regions = new LinkedHashMap<>();
        for (Path sources : SOURCES) {
            regions(sources, regions);
        }
        return regions;
    }

    private static void regions(Path sources, Map<String, String> regions) {
        try (Stream<Path> files = Files.walk(sources)) {
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
