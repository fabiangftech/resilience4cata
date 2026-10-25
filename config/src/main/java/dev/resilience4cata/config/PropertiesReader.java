package dev.resilience4cata.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads {@code .properties} text with the syntax of {@link java.util.Properties#load(java.io.Reader)}
 * (comments, continuation lines, {@code =}/{@code :}/whitespace separators, escapes), but keeps the
 * line number of every key and every duplicate, which {@code Properties} discards.
 */
final class PropertiesReader {

    /**
     * One key and value.
     *
     * @param key the unescaped key
     * @param value the unescaped value
     * @param line the line the entry starts on, from 1
     */
    record Entry(String key, String value, int line) {
    }

    private PropertiesReader() {
    }

    /**
     * Parses the text.
     *
     * @throws IllegalArgumentException with the line number, if an escape is malformed
     */
    static List<Entry> read(String text) {
        List<Entry> entries = new ArrayList<>();
        Lines lines = new Lines(text);
        while (lines.hasNext()) {
            int startLine = lines.line;
            String current = stripLeading(lines.next());
            if (current.isEmpty() || current.charAt(0) == '#' || current.charAt(0) == '!') {
                continue;
            }
            StringBuilder logical = new StringBuilder();
            while (continues(current) && lines.hasNext()) {
                logical.append(current, 0, current.length() - 1);
                current = stripLeading(lines.next());
            }
            logical.append(current, 0, continues(current) ? current.length() - 1 : current.length());
            entries.add(entry(logical.toString(), startLine));
        }
        return entries;
    }

    /** Whether a (stripped) physical line ends with an odd number of backslashes, so it continues. */
    private static boolean continues(String line) {
        int backslashes = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    /** The physical lines of a text, ended by {@code \n}, {@code \r} or {@code \r\n}. */
    private static final class Lines {
        private final String text;
        private int pos;
        /** The number of the line {@link #next()} returns, from 1. */
        int line = 1;

        Lines(String text) {
            this.text = text;
        }

        boolean hasNext() {
            return pos < text.length();
        }

        String next() {
            int length = text.length();
            int end = pos;
            while (end < length && text.charAt(end) != '\n' && text.charAt(end) != '\r') {
                end++;
            }
            String physical = text.substring(pos, end);
            if (end < length && text.charAt(end) == '\r' && end + 1 < length && text.charAt(end + 1) == '\n') {
                pos = end + 2;
            } else {
                pos = end + 1;
            }
            line++;
            return physical;
        }
    }

    private static Entry entry(String logical, int line) {
        int keyEnd = 0;
        boolean escaped = false;
        while (keyEnd < logical.length()) {
            char c = logical.charAt(keyEnd);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '=' || c == ':' || isWhitespace(c)) {
                break;
            }
            keyEnd++;
        }
        int valueStart = keyEnd;
        while (valueStart < logical.length() && isWhitespace(logical.charAt(valueStart))) {
            valueStart++;
        }
        if (valueStart < logical.length() && (logical.charAt(valueStart) == '=' || logical.charAt(valueStart) == ':')) {
            valueStart++;
            while (valueStart < logical.length() && isWhitespace(logical.charAt(valueStart))) {
                valueStart++;
            }
        }
        return new Entry(unescape(logical.substring(0, keyEnd), line), unescape(logical.substring(valueStart), line),
                line);
    }

    private static String unescape(String text, int line) {
        if (text.indexOf('\\') < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c != '\\') {
                out.append(c);
                i++;
            } else if (i + 1 == text.length()) {
                i++;
            } else {
                i = unescapeOne(text, i + 1, out, line);
            }
        }
        return out.toString();
    }

    /**
     * Appends the character escaped at {@code i} (just after a backslash).
     *
     * @return the index after the escape
     */
    private static int unescapeOne(String text, int i, StringBuilder out, int line) {
        char next = text.charAt(i);
        switch (next) {
            case 't' -> out.append('\t');
            case 'n' -> out.append('\n');
            case 'r' -> out.append('\r');
            case 'f' -> out.append('\f');
            case 'u' -> {
                if (i + 5 > text.length()) {
                    throw new IllegalArgumentException("malformed \\uxxxx escape on line " + line);
                }
                try {
                    out.append((char) Integer.parseInt(text.substring(i + 1, i + 5), 16));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("malformed \\uxxxx escape on line " + line, e);
                }
                return i + 5;
            }
            default -> out.append(next);
        }
        return i + 1;
    }

    private static String stripLeading(String text) {
        int i = 0;
        while (i < text.length() && isWhitespace(text.charAt(i))) {
            i++;
        }
        return text.substring(i);
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\f';
    }
}
