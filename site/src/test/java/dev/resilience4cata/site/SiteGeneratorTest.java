package dev.resilience4cata.site;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.site.SiteGenerator.Page;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SiteGeneratorTest {

    private static final Path DOCS = Path.of(System.getProperty("docs.dir"));
    private static final Pattern HREF = Pattern.compile("href=\"([^\"]*)\"");

    @TempDir
    static Path out;
    static List<Page> pages;

    @BeforeAll
    static void generate() throws IOException {
        pages = new SiteGenerator().generate(DOCS, out);
    }

    @Test
    void everyMarkdownPageOfBothLanguagesIsRendered() {
        assertThat(pages).hasSizeGreaterThan(60);
        assertThat(pages.stream().filter(page -> page.language().equals("en")).count())
                .isEqualTo(pages.stream().filter(page -> page.language().equals("es")).count());
        assertThat(pages).allSatisfy(page -> assertThat(out.resolve(page.output())).isRegularFile());
    }

    @Test
    void routesAreThePathsDocsifyUsesInHistoryMode() {
        assertThat(new Page("en", "README.md").route()).isEqualTo("/en/");
        assertThat(new Page("en", "README.md").output()).isEqualTo("en/index.html");
        assertThat(new Page("es", "policies/README.md").route()).isEqualTo("/es/policies/");
        assertThat(new Page("es", "policies/README.md").output()).isEqualTo("es/policies/index.html");
        assertThat(new Page("en", "start/quickstart.md").route()).isEqualTo("/en/start/quickstart");
        assertThat(new Page("en", "start/quickstart.md").output()).isEqualTo("en/start/quickstart.html");
    }

    @Test
    void everyPageHasItsOwnHead() throws IOException {
        for (Page page : pages) {
            String html = Files.readString(out.resolve(page.output()));
            String url = SiteGenerator.BASE_URL + page.route();
            assertThat(html).as(page.output())
                    .contains("<html lang=\"" + page.language() + "\">")
                    .contains("<link rel=\"canonical\" href=\"" + url + "\">")
                    .contains("<meta property=\"og:url\" content=\"" + url + "\">")
                    .contains("hreflang=\"en\" href=\"" + SiteGenerator.BASE_URL + page.in("en").route() + "\"")
                    .contains("hreflang=\"es\" href=\"" + SiteGenerator.BASE_URL + page.in("es").route() + "\"")
                    .contains("hreflang=\"x-default\" href=\"" + SiteGenerator.BASE_URL + page.in("en").route() + "\"")
                    .contains("class=\"r4c-prerender\"")
                    .doesNotContain("<title>resilience4cata</title>")
                    .doesNotContain("<meta name=\"robots\"");
            assertThat(description(html)).as(page.output()).isNotBlank().hasSizeLessThanOrEqualTo(180);
        }
    }

    @Test
    void aPageShowsItsHeadingLedeAndContent() throws IOException {
        String html = Files.readString(out.resolve("en/start/quickstart.html"));
        assertThat(html).contains("<title>Quickstart · resilience4cata</title>")
                .contains("<h1 id=\"quickstart\">Quickstart</h1>")
                .contains("<h2 id=\"_1-add-the-dependencies\">")
                .contains("resilience4cata-all");
        assertThat(description(html)).startsWith("In about five minutes");
    }

    @Test
    void docsifyHeadingIdsAndCalloutsAreHandled() {
        SiteGenerator.Prepared prepared = SiteGenerator.prepare("""
                # Title

                ## Should you migrate? :id=when

                ```java
                # not a heading
                ```

                > [!MEASURED]
                > 2 ns.
                """);
        assertThat(prepared.headingIds()).containsExactly(null, "when");
        assertThat(prepared.markdown()).contains("## Should you migrate?\n").contains("> **Measured**\n")
                .contains("# not a heading");
        String body = new SiteGenerator().renderBody("## Should you migrate? :id=when\n\n## 5. Run it\n\n## 5. Run it\n",
                new Page("en", "x.md"));
        assertThat(body).contains("<h2 id=\"when\">").contains("<h2 id=\"_5-run-it\">").contains("<h2 id=\"_5-run-it-1\">");
    }

    @Test
    void linksToPagesBecomeTheirUrls() {
        Page retry = new Page("en", "policies/retry.md");
        assertThat(SiteGenerator.href("../errors.md#r4c-atr-001", retry)).isEqualTo("/en/errors#r4c-atr-001");
        assertThat(SiteGenerator.href("circuit-breaker.md", retry)).isEqualTo("/en/policies/circuit-breaker");
        assertThat(SiteGenerator.href("README.md", retry)).isEqualTo("/en/policies/");
        assertThat(SiteGenerator.href("../guides/README.md#x", retry)).isEqualTo("/en/guides/#x");
        assertThat(SiteGenerator.href("/es/start/installation.md", retry)).isEqualTo("/es/start/installation");
        assertThat(SiteGenerator.href("#/en/faq", retry)).isEqualTo("/en/faq");
        assertThat(SiteGenerator.href("#configuration", retry)).isEqualTo("#configuration");
        assertThat(SiteGenerator.href("https://github.com/x/y.md", retry)).isEqualTo("https://github.com/x/y.md");
        assertThat(SiteGenerator.href("../_media/og.png", retry)).isEqualTo("../_media/og.png");
    }

    @Test
    void generatedLinksPointAtPagesThatExist() throws IOException {
        for (Page page : pages) {
            Matcher href = HREF.matcher(Files.readString(out.resolve(page.output())));
            while (href.find()) {
                String link = href.group(1);
                if (!link.startsWith("/") || link.startsWith("//")) {
                    continue;
                }
                String path = link.replaceAll("[#?].*$", "");
                Path target = out.resolve(path.substring(1));
                boolean exists = path.endsWith("/") ? Files.isRegularFile(target.resolve("index.html"))
                        : Files.isRegularFile(target) || Files.isRegularFile(target.resolveSibling(target.getFileName() + ".html"));
                assertThat(exists).as(page.output() + " links to " + link).isTrue();
            }
        }
    }

    @Test
    void theShellUsesPathsNotHashRoutes() throws IOException {
        for (String file : List.of("index.html", "en/README.md", "es/README.md")) {
            assertThat(Files.readString(DOCS.resolve(file))).as(file).doesNotContain("href=\"#/");
        }
        assertThat(Files.readString(DOCS.resolve("index.html"))).contains("routerMode: 'history'")
                .doesNotContain("=\"_media/");
    }

    @Test
    void theRootIsTheEnglishLandingAndUnknownUrlsAreNotIndexed() throws IOException {
        assertThat(Files.readString(out.resolve("index.html")))
                .contains("<link rel=\"canonical\" href=\"" + SiteGenerator.BASE_URL + "/en/\">")
                .contains("<title>resilience4cata: resilience, compiled</title>");
        assertThat(Files.readString(out.resolve("404.html")))
                .contains("<meta name=\"robots\" content=\"noindex\">")
                .contains("<div id=\"app\"></div>");
    }

    @Test
    void theSitemapListsEveryPageAndRobotsPointsAtIt() throws IOException {
        String sitemap = Files.readString(out.resolve("sitemap.xml"));
        assertThat(pages).allSatisfy(page ->
                assertThat(sitemap).contains("<loc>" + SiteGenerator.BASE_URL + page.route() + "</loc>"));
        assertThat(Files.readString(out.resolve("robots.txt")))
                .contains("Sitemap: " + SiteGenerator.BASE_URL + "/sitemap.xml");
    }

    @Test
    void docsAreCopiedForDocsifyToFetch() {
        assertThat(out.resolve("en/start/quickstart.md")).isRegularFile();
        assertThat(out.resolve("_media/r4c.js")).isRegularFile();
        assertThat(out.resolve("CNAME")).isRegularFile();
    }

    private static String description(String html) {
        Matcher meta = Pattern.compile("<meta name=\"description\" content=\"([^\"]*)\">").matcher(html);
        return meta.find() ? meta.group(1) : "";
    }
}
