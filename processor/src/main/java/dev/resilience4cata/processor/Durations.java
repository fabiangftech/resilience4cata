package dev.resilience4cata.processor;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the duration strings used in annotations: {@code "250ms"}, {@code "2s"}, {@code "PT2S"}, {@code "0"}. */
final class Durations {

    static final String GRAMMAR = "a number followed by ns, us, ms, s, m, h or d (for example \"250ms\" or \"2s\"),"
            + " an ISO-8601 duration (for example \"PT2S\"), or \"0\"";

    private static final Pattern SIMPLE = Pattern.compile("(\\d+)(ns|us|ms|s|m|h|d)");

    private Durations() {
    }

    /**
     * Returns the duration in nanoseconds.
     *
     * @throws IllegalArgumentException if the text is not a valid, non-negative duration
     */
    static long parseNanos(String text) {
        if (text.equals("0")) {
            return 0;
        }
        if (text.startsWith("P") || text.startsWith("p")) {
            try {
                Duration duration = Duration.parse(text);
                if (duration.isNegative()) {
                    throw new IllegalArgumentException("must not be negative");
                }
                return duration.toNanos();
            } catch (DateTimeParseException | ArithmeticException e) {
                throw new IllegalArgumentException("invalid ISO-8601 duration", e);
            }
        }
        Matcher matcher = SIMPLE.matcher(text);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("unrecognized format");
        }
        try {
            long amount = Long.parseLong(matcher.group(1));
            long unit = switch (matcher.group(2)) {
                case "ns" -> 1L;
                case "us" -> 1_000L;
                case "ms" -> 1_000_000L;
                case "s" -> 1_000_000_000L;
                case "m" -> 60_000_000_000L;
                case "h" -> 3_600_000_000_000L;
                default -> 86_400_000_000_000L;
            };
            return Math.multiplyExact(amount, unit);
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("too large", e);
        }
    }
}
