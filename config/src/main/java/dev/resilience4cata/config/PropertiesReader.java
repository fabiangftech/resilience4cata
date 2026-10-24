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
        int pos = 0;
        int line = 1;
        int length = text.length();
        while (pos < length) {
            int startLine = line;
            StringBuilder logical = new StringBuilder();
            boolean first = true;
            boolean skip = false;
            while (true) {
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
                String stripped = stripLeading(physical);
                if (first && (stripped.isEmpty() || stripped.charAt(0) == '#' || stripped.charAt(0) == '!')) {
                    skip = true;
                    break;
                }
                first = false;
                int backslashes = 0;
                for (int i = stripped.length() - 1; i >= 0 && stripped.charAt(i) == '\\'; i--) {
                    backslashes++;
                }
                if (backslashes % 2 == 1) {
                    logical.append(stripped, 0, stripped.length() - 1);
                    if (pos >= length) {
                        break;
                    }
                } else {
                    logical.append(stripped);
                    break;
                }
            }
            if (!skip) {
                entries.add(entry(logical.toString(), startLine));
            }
        }
        return entries;
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
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\' || i + 1 == text.length()) {
                if (c != '\\') {
                    out.append(c);
                }
                continue;
            }
            char next = text.charAt(++i);
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
                    i += 4;
                }
                default -> out.append(next);
            }
        }
        return out.toString();
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
