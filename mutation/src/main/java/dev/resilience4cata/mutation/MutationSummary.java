package dev.resilience4cata.mutation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Summarises PIT's {@code mutations.xml} in Markdown for the job summary: totals by status, a table per class (most
 * surviving mutants first) and every surviving or uncovered mutant with its line, so that a weak test can be found
 * without opening the HTML report.
 *
 * <p>Arguments: PIT's {@code mutations.xml}, the Markdown file to write.
 */
public final class MutationSummary {

    private static final int LISTED = 300;

    private MutationSummary() {
    }

    public static void main(String[] args) throws Exception {
        Path xml = Path.of(args[0]);
        Path out = Path.of(args[1]);
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        NodeList nodes = factory.newDocumentBuilder().parse(xml.toFile()).getElementsByTagName("mutation");
        List<Mutant> mutants = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element node = (Element) nodes.item(i);
            mutants.add(new Mutant(node.getAttribute("status"), text(node, "mutatedClass"), text(node, "mutatedMethod"),
                    Integer.parseInt(text(node, "lineNumber")), text(node, "description")));
        }
        String report = report(mutants);
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, report);
        System.out.print(report);
    }

    static String report(List<Mutant> mutants) {
        Map<String, Integer> totals = new TreeMap<>();
        Map<String, Map<String, Integer>> byClass = new TreeMap<>();
        for (Mutant mutant : mutants) {
            totals.merge(mutant.status(), 1, Integer::sum);
            byClass.computeIfAbsent(mutant.outerClass(), c -> new TreeMap<>()).merge(mutant.status(), 1, Integer::sum);
        }
        StringBuilder out = new StringBuilder("# Mutation testing of core (PIT)\n\n");
        int killed = totals.getOrDefault("KILLED", 0) + totals.getOrDefault("TIMED_OUT", 0)
                + totals.getOrDefault("MEMORY_ERROR", 0);
        int noCoverage = totals.getOrDefault("NO_COVERAGE", 0);
        out.append(String.format(Locale.ROOT, "%d mutants: %d detected (%.1f %%), %d survived, %d not covered by any "
                        + "test. Test strength (detected among covered): %.1f %%.%n%n", mutants.size(), killed,
                percent(killed, mutants.size()), totals.getOrDefault("SURVIVED", 0), noCoverage,
                percent(killed, mutants.size() - noCoverage)));
        out.append("By status: ").append(totals).append("\n\n");
        out.append("| Class | Mutants | Detected | Survived | Not covered | Strength |\n|---|---:|---:|---:|---:|---:|\n");
        byClass.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<String, Map<String, Integer>> e) ->
                        -e.getValue().getOrDefault("SURVIVED", 0) - e.getValue().getOrDefault("NO_COVERAGE", 0))
                        .thenComparing(Map.Entry::getKey))
                .forEach(e -> {
                    Map<String, Integer> s = e.getValue();
                    int total = s.values().stream().mapToInt(Integer::intValue).sum();
                    int detected = s.getOrDefault("KILLED", 0) + s.getOrDefault("TIMED_OUT", 0)
                            + s.getOrDefault("MEMORY_ERROR", 0);
                    int uncovered = s.getOrDefault("NO_COVERAGE", 0);
                    out.append(String.format(Locale.ROOT, "| `%s` | %d | %d | %d | %d | %.0f %% |%n",
                            e.getKey().replace("dev.resilience4cata.", ""), total, detected,
                            s.getOrDefault("SURVIVED", 0), uncovered, percent(detected, total - uncovered)));
                });
        List<Mutant> undetected = mutants.stream()
                .filter(m -> m.status().equals("SURVIVED") || m.status().equals("NO_COVERAGE"))
                .sorted(Comparator.comparing(Mutant::mutatedClass).thenComparingInt(Mutant::line))
                .toList();
        out.append("\n## Undetected mutants\n\n");
        undetected.stream().limit(LISTED).forEach(m -> out.append(String.format(Locale.ROOT, "- `%s:%d` %s: %s%s%n",
                m.mutatedClass().replace("dev.resilience4cata.", ""), m.line(), m.method(), m.description(),
                m.status().equals("NO_COVERAGE") ? " (not covered)" : "")));
        if (undetected.size() > LISTED) {
            out.append(String.format(Locale.ROOT, "- … and %d more in the HTML report%n", undetected.size() - LISTED));
        }
        return out.toString();
    }

    private static double percent(int part, int whole) {
        return whole == 0 ? 100 : 100.0 * part / whole;
    }

    private static String text(Element node, String tag) {
        return node.getElementsByTagName(tag).item(0).getTextContent();
    }

    record Mutant(String status, String mutatedClass, String method, int line, String description) {

        /** The top-level class, so that a policy's nested classes count with it. */
        String outerClass() {
            int nested = mutatedClass.indexOf('$');
            return nested < 0 ? mutatedClass : mutatedClass.substring(0, nested);
        }
    }
}
