package dev.resilience4cata.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The processor parses the durations of annotations and this module those of external configuration, with two copies
 * of one grammar (the processor is never on an application's run-time class path). Both must give the same result for
 * any text: the same nanoseconds, or a rejection with the same message, so that a duration valid in an annotation is
 * valid in a file and means the same. Random texts near the grammar (numbers at the overflow edges, every unit in
 * several spellings, ISO-8601 forms, signs, spaces, junk), from the seed {@code -PfuzzStart} (the nightly fuzz
 * workflow passes a new one); the processor's class is loaded from its jar ({@code test.processorJar}).
 */
class DurationsAgreementTest {

    private static final int TEXTS = 200_000;

    private static final List<String> AMOUNTS = List.of("0", "1", "7", "250", "007", "00", "59", "60", "1000",
            "106751", "106752", "153722867", "153722868", "9223372036", "9223372037", "9223372036854775807",
            "9223372036854775808", "99999999999999999999", "", "-1", "+1", "1.5", ".5", "1e3", "１", "٣", "1_000");
    private static final List<String> UNITS = List.of("ns", "us", "ms", "s", "m", "h", "d", "NS", "Ms", "S", "M",
            "H", "D", "µs", "sec", "min", "w", "", " s", "s ", "mss");
    private static final List<String> ISO_PARTS = List.of("1D", "T", "2H", "3M", "4S", "4.5S", "0.000000001S",
            "0.0000000001S", "-1S", "-0.5S", "+1S", "1W", "1Y", "-1D", "106751D", "106752D", "9223372036854775807S",
            "153722867M", "2562047H", "2562048H", ",5S", "1.S", "S", "1");
    private static final String JUNK = "0123456789PpTtDdHhMmSsnu.,-+ _µ";

    private static URLClassLoader processor;
    private static Method processorParse;

    @BeforeAll
    static void loadTheProcessorsParser() throws Exception {
        String jar = System.getProperty("test.processorJar");
        assertThat(jar).as("set by the build (test.processorJar)").isNotBlank();
        URL[] urls = new URL[] {new File(jar.split(File.pathSeparator)[0]).toURI().toURL()};
        processor = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
        Class<?> durations = Class.forName("dev.resilience4cata.processor.Durations", true, processor);
        processorParse = durations.getDeclaredMethod("parseNanos", String.class);
        processorParse.setAccessible(true);
    }

    @AfterAll
    static void close() throws Exception {
        processor.close();
    }

    @Test
    void bothParsersDescribeTheSameGrammar() throws Exception {
        var grammar = processorParse.getDeclaringClass().getDeclaredField("GRAMMAR");
        grammar.setAccessible(true);
        assertThat(grammar.get(null)).isEqualTo(Durations.GRAMMAR);
    }

    @Test
    void bothParsersAgreeOnEveryText() throws Exception {
        long seed = Long.parseLong(System.getProperty("test.fuzz.start", "1"));
        SplittableRandom random = new SplittableRandom(seed);
        int accepted = 0;
        for (int i = 0; i < TEXTS; i++) {
            String text = text(random);
            String config = outcome(() -> Durations.parseNanos(text));
            String annotations = processorOutcome(text);
            assertThat(config).as("Durations.parseNanos(\"%s\") in config and in the processor (seed %d, "
                    + "replay with -PfuzzStart=%d)", text, seed, seed).isEqualTo(annotations);
            if (!config.startsWith("rejected")) {
                accepted++;
            }
        }
        // The texts reach both sides of the grammar, not only its errors.
        assertThat(accepted).as("texts accepted").isBetween(TEXTS / 20, TEXTS * 19 / 20);
    }

    private static String text(SplittableRandom random) {
        String text = switch (random.nextInt(4)) {
            case 0, 1 -> pick(random, AMOUNTS) + pick(random, UNITS);
            case 2 -> iso(random);
            default -> junk(random);
        };
        return switch (random.nextInt(12)) {
            case 0 -> " " + text;
            case 1 -> text + "\t";
            case 2 -> text.toUpperCase(Locale.ROOT);
            default -> text;
        };
    }

    private static String iso(SplittableRandom random) {
        StringBuilder text = new StringBuilder(random.nextBoolean() ? "P" : random.nextBoolean() ? "p" : "-P");
        if (random.nextInt(3) > 0) {
            text.append(random.nextBoolean() ? "T" : "t");
        }
        for (int parts = random.nextInt(4); parts > 0; parts--) {
            text.append(pick(random, ISO_PARTS));
        }
        return text.toString();
    }

    private static String junk(SplittableRandom random) {
        StringBuilder text = new StringBuilder();
        for (int length = random.nextInt(9); length > 0; length--) {
            text.append(JUNK.charAt(random.nextInt(JUNK.length())));
        }
        return text.toString();
    }

    private static String pick(SplittableRandom random, List<String> values) {
        return values.get(random.nextInt(values.size()));
    }

    private static String processorOutcome(String text) {
        return outcome(() -> {
            try {
                return (Long) processorParse.invoke(null, text);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof RuntimeException failure) {
                    throw failure;
                }
                throw new AssertionError(e.getCause());
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        });
    }

    /** The value, or the rejection with its message and its cause's type (the processor reports both). */
    private static String outcome(LongSupplier parse) {
        try {
            return Long.toString(parse.getAsLong());
        } catch (IllegalArgumentException e) {
            return "rejected: " + e.getMessage() + " (cause "
                    + Objects.toString(e.getCause() == null ? null : e.getCause().getClass().getName()) + ")";
        }
    }
}
