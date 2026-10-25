package dev.resilience4cata;

import java.util.Collection;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * "Did you mean …?" for names looked up in the registry: the closest candidate by edit distance, ignoring
 * case, {@code '-'} and {@code '_'}. Same rule as the processor's and the config module's helpers.
 */
final class Suggestions {

    private Suggestions() {
    }

    /** Returns {@code " Did you mean 'x'?"} for the closest candidate, or {@code ""} if none is close enough. */
    static String didYouMean(String text, Collection<String> candidates) {
        String closest = closest(text, candidates);
        return closest == null ? "" : " Did you mean '" + closest + "'?";
    }

    /** Returns the candidate closest to {@code text}, or null if none is close enough. */
    static @Nullable String closest(String text, Collection<String> candidates) {
        String normalized = normalize(text);
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            int distance = distance(normalized, normalize(candidate));
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        int allowed = Math.max(1, Math.min(3, normalized.length() / 3));
        return bestDistance <= allowed ? best : null;
    }

    private static String normalize(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '-' && c != '_') {
                out.append(c);
            }
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }

    /** Optimal string alignment distance: Levenshtein plus adjacent transpositions. */
    static int distance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }
}
