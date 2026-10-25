package dev.resilience4cata.processor;

import java.util.Collection;
import java.util.Locale;

/** "Did you mean …?" support: the closest name by edit distance, ignoring case. */
final class Suggestions {

    private Suggestions() {
    }

    /** Returns the candidate closest to {@code text}, or null if none is close enough. */
    static String closest(String text, Collection<String> candidates) {
        String normalized = text.toLowerCase(Locale.ROOT);
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            int distance = distance(normalized, candidate.toLowerCase(Locale.ROOT));
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        int allowed = Math.clamp(normalized.length() / 3, 1, 3);
        return bestDistance <= allowed ? best : null;
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
