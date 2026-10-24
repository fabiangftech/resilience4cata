package dev.resilience4cata.config;

/**
 * One parsed key and value.
 *
 * @param key the parsed key
 * @param value the parsed value: a policy attribute value, a config name ({@code extends}) or a list
 *     of profile names ({@code profiles.active})
 * @param text the value as written, trimmed
 * @param origin the key as written and where it comes from, for messages
 */
record Setting(Key key, Object value, String text, String origin) {
}
