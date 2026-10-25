package dev.resilience4cata.build;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/**
 * Compares the bytes allocated per operation ({@code gc.alloc.rate.norm} from JMH's {@code -prof gc})
 * of each benchmark listed in the budgets file with its budget, and fails if any is over budget or
 * missing from the results.
 *
 * <p>The budgets file is a properties file: {@code <BenchmarkClass>.<method> = <bytes per op>}. A
 * benchmark passes if it allocates less than its budget plus {@value #TOLERANCE} byte, so a budget of
 * 0 accepts the fractions of a byte that JMH's own bookkeeping adds over a short run, but not one
 * object per call (16 bytes at least).
 */
@DisableCachingByDefault(because = "The results come from a fresh benchmark run every time")
public abstract class AllocationCheck extends DefaultTask {

    /** Bytes per operation accepted above each budget. */
    static final double TOLERANCE = 1.0;

    private static final String METRIC = "gc.alloc.rate.norm";

    /** The budgets file. */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getBudgets();

    /** JMH's JSON result file. */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getResults();

    /** Markdown report, for CI summaries. */
    @OutputFile
    public abstract RegularFileProperty getReport();

    @TaskAction
    void check() throws IOException {
        Map<String, Double> budgets = readBudgets(getBudgets().get().getAsFile().toPath());
        Map<String, Double> measured = readResults(getResults().get().getAsFile().toPath());

        List<String[]> rows = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        budgets.forEach((name, budget) -> {
            Double bytes = measured.get(name);
            String status;
            if (bytes == null) {
                status = "MISSING";
                failures.add(name + ": no " + METRIC + " result");
            } else if (bytes >= budget + TOLERANCE) {
                status = "OVER BUDGET";
                failures.add(String.format(Locale.ROOT, "%s: %.2f B/op, budget %s B/op", name, bytes, format(budget)));
            } else {
                status = "ok";
            }
            rows.add(new String[] {name, bytes == null ? "-" : String.format(Locale.ROOT, "%.2f", bytes), format(budget), status});
        });

        String table = table(rows);
        Files.writeString(getReport().get().getAsFile().toPath(), markdown(rows, failures.isEmpty()), StandardCharsets.UTF_8);
        getLogger().lifecycle("Allocation per operation (JMH -prof gc, " + METRIC + "):\n" + table);
        if (!failures.isEmpty()) {
            throw new GradleException("Allocation gate failed for " + failures.size() + " of " + budgets.size()
                    + " benchmarks:\n  " + String.join("\n  ", failures)
                    + "\nOver budget: a success or rejection path started allocating. Find the allocation with"
                    + " ./gradlew :benchmark:jmh -Pjmh=\"<benchmark> -prof gc\" (or a JFR allocation profile);"
                    + " raise the budget only for an allocation that is intended and documented."
                    + "\nMissing: the benchmark was renamed or removed; update "
                    + getBudgets().get().getAsFile().getName() + ".");
        }
    }

    private static Map<String, Double> readBudgets(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        Map<String, Double> budgets = new TreeMap<>();
        for (String name : properties.stringPropertyNames()) {
            String value = properties.getProperty(name).trim();
            try {
                budgets.put(name, Double.valueOf(value));
            } catch (NumberFormatException e) {
                throw new GradleException(file.getFileName() + ": budget of " + name + " is not a number: '" + value + "'", e);
            }
        }
        if (budgets.isEmpty()) {
            throw new GradleException(file.getFileName() + " lists no benchmarks");
        }
        return budgets;
    }

    /** Maps {@code <BenchmarkClass>.<method>} to its bytes per operation. */
    private static Map<String, Double> readResults(Path file) throws IOException {
        Map<String, Double> measured = new TreeMap<>();
        if (!(JsonReader.parse(Files.readString(file, StandardCharsets.UTF_8)) instanceof List<?> runs)) {
            throw new GradleException(file + " is not a JMH JSON result file");
        }
        for (Object run : runs) {
            if (run instanceof Map<?, ?> result
                    && result.get("benchmark") instanceof String benchmark
                    && result.get("secondaryMetrics") instanceof Map<?, ?> secondary
                    && secondary.get(METRIC) instanceof Map<?, ?> metric
                    && metric.get("score") instanceof Double score) {
                String[] parts = benchmark.split("\\.");
                String name = parts.length < 2 ? benchmark : parts[parts.length - 2] + "." + parts[parts.length - 1];
                measured.merge(name, score, Math::max);
            }
        }
        return measured;
    }

    /** Include patterns for JMH, one per budgeted benchmark. */
    static List<String> includes(Path budgetsFile) {
        try {
            List<String> includes = new ArrayList<>();
            for (String name : readBudgets(budgetsFile).keySet()) {
                includes.add("\\." + name.replace(".", "\\.") + "$");
            }
            return includes;
        } catch (IOException e) {
            throw new GradleException("Cannot read " + budgetsFile, e);
        }
    }

    private static String format(double budget) {
        return budget == Math.rint(budget) ? Long.toString((long) budget) : Double.toString(budget);
    }

    private static String table(List<String[]> rows) {
        String[] header = {"Benchmark", "B/op", "Budget", "Status"};
        int[] width = new int[header.length];
        for (int i = 0; i < header.length; i++) {
            width[i] = header[i].length();
            for (String[] row : rows) {
                width[i] = Math.max(width[i], row[i].length());
            }
        }
        StringBuilder out = new StringBuilder();
        line(out, header, width);
        StringBuilder rule = new StringBuilder();
        for (int w : width) {
            rule.append("-".repeat(w)).append("  ");
        }
        out.append(rule.toString().stripTrailing()).append('\n');
        for (String[] row : rows) {
            line(out, row, width);
        }
        return out.toString();
    }

    private static void line(StringBuilder out, String[] cells, int[] width) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            String cell = cells[i];
            // Left-align the name and status, right-align the numbers.
            line.append(i == 1 || i == 2 ? " ".repeat(width[i] - cell.length()) + cell : cell + " ".repeat(width[i] - cell.length()));
            line.append("  ");
        }
        out.append(line.toString().stripTrailing()).append('\n');
    }

    private static String markdown(List<String[]> rows, boolean passed) {
        StringBuilder out = new StringBuilder("## Allocation gate: ").append(passed ? "passed" : "FAILED").append("\n\n");
        out.append("| Benchmark | B/op | Budget | Status |\n|---|---:|---:|---|\n");
        for (String[] row : rows) {
            out.append("| `").append(row[0]).append("` | ").append(row[1]).append(" | ").append(row[2]).append(" | ")
                    .append(row[3].equals("ok") ? "ok" : "**" + row[3] + "**").append(" |\n");
        }
        return out.toString();
    }
}
