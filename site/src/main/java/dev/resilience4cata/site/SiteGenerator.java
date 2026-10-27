package dev.resilience4cata.site;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

/**
 * Builds the documentation site: a copy of {@code docs/}, plus one static HTML page per Markdown page, so that search
 * engines (which ignore Docsify's client-side rendering of an empty shell) see each page at its own URL with its own
 * title, description, canonical URL, {@code hreflang} alternates and content.
 *
 * <p>Docsify runs in history mode: {@code /en/start/quickstart} fetches {@code /en/start/quickstart.md} and replaces
 * the pre-rendered content once the page loads. GitHub Pages serves {@code quickstart.html} for that URL, and a
 * folder's {@code index.html} for {@code /en/policies/}. Unknown URLs get {@code 404.html}, the plain Docsify shell,
 * which shows the Markdown 404 page.
 *
 * <p>Usage: {@code SiteGenerator <docs dir> <output dir>}.
 */
public final class SiteGenerator {

    static final String BASE_URL = "https://resilience4cata.dev";
    static final List<String> LANGUAGES = List.of("en", "es");

    private static final Map<String, String> LANDING_TITLE = Map.of(
            "en", "resilience4cata: resilience, compiled",
            "es", "resilience4cata: resiliencia, compilada");
    private static final Map<String, String> LANDING_DESCRIPTION = Map.of(
            "en", "Annotation-driven resilience for plain Java 21+: retry, circuit breaker, rate limiter, timeout, "
                    + "bulkhead, hedging and more, generated at compile time. No proxies, no framework.",
            "es", "Resiliencia con anotaciones para Java 21+: retry, circuit breaker, rate limiter, timeout, "
                    + "bulkhead, hedging y más, generados al compilar. Sin proxies, sin framework.");
    private static final Map<String, String> LOCALE = Map.of("en", "en_US", "es", "es_ES");
    private static final int DESCRIPTION_LENGTH = 160;

    private static final Pattern HEADING_ID = Pattern.compile("^(#{1,6}\\s.*?)\\s+:id=([\\w-]+)\\s*$");
    private static final Pattern CALLOUT = Pattern.compile("^(\\s*>\\s*)\\[!(\\w+)(\\|[^\\]]*)?\\]\\s*$");
    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~)");

    private final Parser parser;

    SiteGenerator() {
        List<Extension> extensions = List.of(TablesExtension.create());
        this.parser = Parser.builder().extensions(extensions).build();
    }

    /** One Markdown page: {@code en/start/quickstart.md} is served at {@code /en/start/quickstart}. */
    record Page(String language, String file) {

        /** The URL path: a folder's {@code README.md} is the folder ({@code /en/policies/}). */
        String route() {
            String path = "/" + language + "/" + file.substring(0, file.length() - ".md".length());
            return path.endsWith("README") ? path.substring(0, path.length() - "README".length()) : path;
        }

        /** The output file, relative to the site root: {@code en/start/quickstart.html}, {@code en/policies/index.html}. */
        String output() {
            String route = route().substring(1);
            return route.endsWith("/") ? route + "index.html" : route + ".html";
        }

        /** The same page in another language (the en and es trees are identical, which DocsTest checks). */
        Page in(String other) {
            return new Page(other, file);
        }
    }

    /** What a page shows: its title, description and rendered body. */
    record Rendered(String title, String description, String body) {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: SiteGenerator <docs dir> <output dir>");
        }
        List<Page> pages = new SiteGenerator().generate(Path.of(args[0]), Path.of(args[1]));
        System.out.println("Site: " + pages.size() + " pages in " + args[1]);
    }

    /** Copies {@code docs} into {@code out} and adds the pre-rendered pages, {@code 404.html}, the sitemap and robots.txt. */
    List<Page> generate(Path docs, Path out) throws IOException {
        deleteRecursively(out);
        copyRecursively(docs, out);
        String template = Files.readString(docs.resolve("index.html"));
        List<Page> pages = pages(docs);
        Map<String, String> sidebars = new HashMap<>();
        for (String language : LANGUAGES) {
            Page sidebar = new Page(language, "_sidebar.md");
            sidebars.put(language, renderBody(Files.readString(docs.resolve(language).resolve("_sidebar.md")), sidebar));
        }
        for (Page page : pages) {
            Rendered rendered = render(Files.readString(docs.resolve(page.language()).resolve(page.file())), page);
            write(out.resolve(page.output()), html(template, page, rendered, sidebars.get(page.language())));
            if (page.language().equals("en") && page.file().equals("README.md")) {
                // The site root redirects to /en/ or /es/ by the reader's language; crawlers get the English landing.
                write(out.resolve("index.html"), html(template, page, rendered, sidebars.get("en")));
            }
        }
        write(out.resolve("404.html"), template.replace("<meta name=\"viewport\"",
                "<meta name=\"robots\" content=\"noindex\">\n  <meta name=\"viewport\""));
        write(out.resolve("sitemap.xml"), sitemap(pages));
        write(out.resolve("robots.txt"), "User-agent: *\nAllow: /\n\nSitemap: " + BASE_URL + "/sitemap.xml\n");
        return pages;
    }

    /** Every Markdown page of both languages; files starting with {@code _} (sidebar, 404) are not pages. */
    static List<Page> pages(Path docs) throws IOException {
        List<Page> pages = new ArrayList<>();
        for (String language : LANGUAGES) {
            Path root = docs.resolve(language);
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(Files::isRegularFile)
                        .filter(file -> file.getFileName().toString().endsWith(".md"))
                        .filter(file -> !file.getFileName().toString().startsWith("_"))
                        .map(file -> root.relativize(file).toString().replace('\\', '/'))
                        .sorted(Comparator.naturalOrder())
                        .forEach(file -> pages.add(new Page(language, file)));
            }
        }
        return pages;
    }

    Rendered render(String markdown, Page page) {
        String body = renderBody(markdown, page);
        if (page.file().equals("README.md")) {
            return new Rendered(LANDING_TITLE.get(page.language()), LANDING_DESCRIPTION.get(page.language()), body);
        }
        Node document = parser.parse(prepare(markdown).markdown());
        String title = null;
        String description = null;
        for (Node node = document.getFirstChild(); node != null; node = node.getNext()) {
            if (title == null && node instanceof Heading heading && heading.getLevel() == 1) {
                title = plainText(heading).strip();
            } else if (title != null && node instanceof Paragraph paragraph) {
                description = shorten(plainText(paragraph).replaceAll("\\s+", " ").strip());
                break;
            }
        }
        if (title == null) {
            throw new IllegalStateException(page.language() + "/" + page.file() + " has no '# Title' heading");
        }
        return new Rendered(title + " · resilience4cata",
                description == null ? LANDING_DESCRIPTION.get(page.language()) : description, body);
    }

    /** The page's Markdown as HTML: heading ids as Docsify makes them, links to pages as their URLs. */
    String renderBody(String markdown, Page page) {
        Prepared prepared = prepare(markdown);
        Node document = parser.parse(prepared.markdown());
        List<String> explicitIds = prepared.headingIds();
        int[] heading = {0};
        Map<String, Integer> used = new HashMap<>();
        HtmlRenderer renderer = HtmlRenderer.builder()
                .extensions(List.of(TablesExtension.create()))
                .attributeProviderFactory(context -> (node, tag, attributes) -> {
                    if (node instanceof Heading h) {
                        String explicit = heading[0] < explicitIds.size() ? explicitIds.get(heading[0]) : null;
                        heading[0]++;
                        attributes.put("id", explicit != null ? explicit : unique(slug(plainText(h)), used));
                    } else if (node instanceof Link link) {
                        attributes.put("href", href(link.getDestination(), page));
                    }
                })
                .build();
        return renderer.render(document);
    }

    /** The text of a node, code included, without the quotes {@code TextContentRenderer} puts around code. */
    static String plainText(Node node) {
        StringBuilder out = new StringBuilder();
        node.accept(new AbstractVisitor() {
            @Override
            public void visit(Text text) {
                out.append(text.getLiteral());
            }

            @Override
            public void visit(Code code) {
                out.append(code.getLiteral());
            }

            @Override
            public void visit(SoftLineBreak lineBreak) {
                out.append(' ');
            }

            @Override
            public void visit(HardLineBreak lineBreak) {
                out.append(' ');
            }
        });
        return out.toString();
    }

    /** Markdown with Docsify's syntax removed: {@code :id=} on headings (kept as ids) and callout markers. */
    record Prepared(String markdown, List<String> headingIds) {
    }

    static Prepared prepare(String markdown) {
        StringBuilder out = new StringBuilder();
        List<String> ids = new ArrayList<>();
        boolean fenced = false;
        for (String line : markdown.split("\n", -1)) {
            if (FENCE.matcher(line).find()) {
                fenced = !fenced;
            } else if (!fenced) {
                if (line.startsWith("#")) {
                    Matcher id = HEADING_ID.matcher(line);
                    if (id.matches()) {
                        line = id.group(1);
                        ids.add(id.group(2));
                    } else if (line.matches("^#{1,6}\\s.*")) {
                        ids.add(null);
                    }
                }
                Matcher callout = CALLOUT.matcher(line);
                if (callout.matches()) {
                    line = callout.group(1) + "**" + label(callout.group(2)) + "**";
                }
            }
            out.append(line).append('\n');
        }
        return new Prepared(out.toString(), ids);
    }

    private static String label(String type) {
        return type.charAt(0) + type.substring(1).toLowerCase(Locale.ROOT);
    }

    /**
     * A link as the site serves it: {@code ../errors.md#r4c-atr-001} from {@code /en/policies/retry} is
     * {@code /en/errors#r4c-atr-001}; old hash routes ({@code #/en/x}) become paths; other links are unchanged.
     */
    static String href(String destination, Page page) {
        if (destination.startsWith("#/")) {
            return destination.substring(1);
        }
        if (destination.startsWith("#") || destination.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) {
            return destination;
        }
        int hash = destination.indexOf('#');
        String path = hash < 0 ? destination : destination.substring(0, hash);
        String fragment = hash < 0 ? "" : destination.substring(hash);
        if (!path.endsWith(".md")) {
            return destination;
        }
        String resolved = path.startsWith("/") ? path
                : URI.create("https://x" + directory(page)).resolve(path).getPath();
        String route = resolved.substring(0, resolved.length() - ".md".length());
        if (route.endsWith("/README")) {
            route = route.substring(0, route.length() - "README".length());
        }
        return route + fragment;
    }

    /** The folder a page's relative links resolve against: the folder of its Markdown file. */
    private static String directory(Page page) {
        String file = "/" + page.language() + "/" + page.file();
        return file.substring(0, file.lastIndexOf('/') + 1);
    }

    /** Docsify's heading slug: lowercase, punctuation removed, spaces as dashes, {@code _} before a leading digit. */
    static String slug(String heading) {
        String slug = heading.strip().toLowerCase(Locale.ROOT)
                .replaceAll("<[^>\\d]+>", "")
                .replaceAll("[\\u2000-\\u206F\\u2E00-\\u2E7F\\\\'!\"#$%&()*+,./:;<=>?@\\[\\]^`{|}~]", "")
                .replaceAll("\\s", "-");
        return !slug.isEmpty() && Character.isDigit(slug.charAt(0)) ? "_" + slug : slug;
    }

    private static String unique(String slug, Map<String, Integer> used) {
        Integer count = used.get(slug);
        used.put(slug, count == null ? 1 : count + 1);
        return count == null ? slug : slug + "-" + count;
    }

    private static String shorten(String description) {
        if (description.length() <= DESCRIPTION_LENGTH) {
            return description;
        }
        int cut = description.lastIndexOf(' ', DESCRIPTION_LENGTH - 1);
        return description.substring(0, cut > 0 ? cut : DESCRIPTION_LENGTH - 1) + "…";
    }

    /** The Docsify shell with this page's head and its content in {@code #app}, for readers that run no script. */
    static String html(String template, Page page, Rendered rendered, String sidebar) {
        String url = BASE_URL + page.route();
        String title = escape(rendered.title());
        String description = escape(rendered.description());
        StringBuilder alternates = new StringBuilder();
        for (String language : LANGUAGES) {
            alternates.append("\n  <link rel=\"alternate\" hreflang=\"").append(language).append("\" href=\"")
                    .append(BASE_URL).append(page.in(language).route()).append("\">");
        }
        alternates.append("\n  <link rel=\"alternate\" hreflang=\"x-default\" href=\"")
                .append(BASE_URL).append(page.in("en").route()).append("\">");
        String html = template;
        html = replace(html, "<html lang=\"en\">", "<html lang=\"" + page.language() + "\">");
        html = replace(html, "<title>resilience4cata</title>", "<title>" + title + "</title>");
        html = replaceAttribute(html, "name=\"description\"", description);
        html = replace(html, "<link rel=\"canonical\" href=\"" + BASE_URL + "/\">",
                "<link rel=\"canonical\" href=\"" + url + "\">" + alternates);
        html = replaceAttribute(html, "property=\"og:url\"", url);
        html = replaceAttribute(html, "property=\"og:title\"", title);
        html = replaceAttribute(html, "property=\"og:description\"", description);
        html = replaceAttribute(html, "name=\"twitter:title\"", title);
        html = replaceAttribute(html, "name=\"twitter:description\"", description);
        html = replace(html, "<meta property=\"og:site_name\"",
                "<meta property=\"og:locale\" content=\"" + LOCALE.get(page.language()) + "\">\n  <meta property=\"og:site_name\"");
        return replace(html, "<div id=\"app\"></div>",
                "<div id=\"app\"><div class=\"r4c-prerender\"><nav aria-label=\"Documentation\">" + sidebar
                        + "</nav><main><article class=\"markdown-section\">" + rendered.body()
                        + "</article></main></div></div>");
    }

    private static String replace(String html, String target, String replacement) {
        if (!html.contains(target)) {
            throw new IllegalStateException("docs/index.html no longer contains " + target);
        }
        return html.replace(target, replacement);
    }

    /** Replaces the {@code content} of the {@code <meta>} with the given name or property. */
    private static String replaceAttribute(String html, String key, String content) {
        Matcher meta = Pattern.compile("(<meta " + Pattern.quote(key) + " content=\")[^\"]*(\">)").matcher(html);
        if (!meta.find()) {
            throw new IllegalStateException("docs/index.html has no <meta " + key + " content=\"…\">");
        }
        return meta.replaceFirst(Matcher.quoteReplacement(meta.group(1) + content + meta.group(2)));
    }

    static String sitemap(List<Page> pages) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\" xmlns:xhtml=\"http://www.w3.org/1999/xhtml\">\n");
        for (Page page : pages) {
            xml.append("  <url>\n    <loc>").append(BASE_URL).append(page.route()).append("</loc>\n");
            for (String language : LANGUAGES) {
                xml.append("    <xhtml:link rel=\"alternate\" hreflang=\"").append(language).append("\" href=\"")
                        .append(BASE_URL).append(page.in(language).route()).append("\"/>\n");
            }
            xml.append("  </url>\n");
        }
        return xml.append("</urlset>\n").toString();
    }

    static String escape(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static void copyRecursively(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            files.forEach(source -> {
                Path target = to.resolve(from.relativize(source).toString());
                try {
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(target);
                    } else {
                        Files.copy(source, target);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        }
    }
}
