package dev.resilience4cata.config;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A parsed configuration key. Canonical form, lowest precedence first:
 * <pre>
 * defaults.&lt;policy&gt;.&lt;attribute&gt;
 * configs.&lt;name&gt;.&lt;policy&gt;.&lt;attribute&gt;
 * configs.&lt;name&gt;.extends
 * instances.&lt;id&gt;.&lt;policy&gt;.&lt;attribute&gt;     (id quoted, "...", unless it is a plain name)
 * profiles.&lt;profile&gt;.(any of the above)
 * profiles.active
 * </pre>
 *
 * @param profile the profile the key belongs to, or null
 * @param section what the key sets
 * @param config the named config, for {@link Section#CONFIG} and {@link Section#EXTENDS}
 * @param instance the instance id, unquoted, for {@link Section#INSTANCE}
 * @param policy the policy, for {@link Section#DEFAULTS}, {@link Section#CONFIG} and {@link Section#INSTANCE}
 * @param attribute the canonical attribute name, for {@link Section#DEFAULTS}, {@link Section#CONFIG} and
 *     {@link Section#INSTANCE}
 */
record Key(@Nullable String profile, Section section, @Nullable String config, @Nullable String instance,
        @Nullable PolicyType<?, ?> policy, @Nullable String attribute) {

    /** What a key sets. */
    enum Section {
        ACTIVE_PROFILES, DEFAULTS, CONFIG, EXTENDS, INSTANCE
    }

    /** Instance ids written without quotes in canonical keys; any other id is quoted. */
    private static final Pattern PLAIN_ID = Pattern.compile("[A-Za-z0-9_-]+");

    static final Pattern NAME = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final List<String> TOP_LEVEL = List.of("defaults", "configs", "instances", "profiles");
    private static final String FORMS = "expected defaults.<policy>.<attribute>, configs.<name>.<policy>.<attribute>,"
            + " configs.<name>.extends, instances.<id>.<policy>.<attribute>, profiles.<profile>.<one of those> or"
            + " profiles.active";
    private static final String INSTANCE_FORM = "instances.<id>.<policy>.<attribute>, where <id> is the instance"
            + " name, or its derived id in double quotes: instances.\"com.acme.Api#get(java.lang.String)\".retry.maxAttempts";

    String canonical() {
        String prefix = profile == null ? "" : "profiles." + profile + ".";
        return switch (section) {
            case ACTIVE_PROFILES -> "profiles.active";
            case DEFAULTS -> prefix + "defaults." + policy + "." + attribute;
            case CONFIG -> prefix + "configs." + config + "." + policy + "." + attribute;
            case EXTENDS -> prefix + "configs." + config + ".extends";
            case INSTANCE -> prefix + "instances." + quoted(java.util.Objects.requireNonNull(instance)) + "." + policy
                    + "." + attribute;
        };
    }

    /** Writes an instance id as a key segment: as is if it is a plain name, otherwise in double quotes. */
    static String quoted(String id) {
        return PLAIN_ID.matcher(id).matches() ? id : '"' + id + '"';
    }

    Key withoutProfile() {
        return new Key(null, section, config, instance, policy, attribute);
    }

    /**
     * Parses a dotted key, without the optional {@code resilience4cata.}/{@code r4c.} prefix.
     *
     * @param written the key as written, for messages
     * @param origin where the key comes from, for messages
     * @throws ProblemException if the key is not valid
     */
    static Key parse(String key, String written, String origin) {
        String[] s = segments(key, origin);
        for (String segment : s) {
            if (segment.isEmpty()) {
                throw new ProblemException(ProblemException.UNKNOWN_KEY, "malformed key " + origin
                        + ": empty segment; " + FORMS);
            }
        }
        for (int j = 0; j < s.length; j++) {
            boolean idPosition = j >= 1 && is(s[j - 1], "instances") && (j == 1 || j == 3 && is(s[0], "profiles"));
            if (s[j].startsWith("\"") && !idPosition) {
                throw new ProblemException(ProblemException.INVALID_INSTANCE_ID, "malformed key " + origin
                        + ": only an instance id may be quoted (" + INSTANCE_FORM + ")");
            }
        }
        String profile = null;
        int i = 0;
        if (is(s[0], "profiles")) {
            if (s.length == 2 && is(s[1], "active")) {
                return new Key(null, Section.ACTIVE_PROFILES, null, null, null, null);
            }
            if (s.length < 3) {
                throw new ProblemException(ProblemException.UNKNOWN_KEY, "incomplete key " + origin + ": " + FORMS);
            }
            profile = name(s[1], "profile", origin);
            if (profile.equals("active")) {
                throw new ProblemException(ProblemException.INVALID_NAME, "invalid key " + origin
                        + ": 'active' is reserved (profiles.active lists the active profiles)");
            }
            i = 2;
        }
        String head = s[i];
        if (is(head, "defaults")) {
            if (s.length - i != 3) {
                throw new ProblemException(ProblemException.UNKNOWN_KEY, "incomplete key " + origin
                        + ": expected " + (profile == null ? "" : "profiles." + profile + ".")
                        + "defaults.<policy>.<attribute>");
            }
            PolicyType<?, ?> policy = policy(s, i + 1, written, origin);
            return new Key(profile, Section.DEFAULTS, null, null, policy, attribute(policy, s, i + 2, written, origin));
        }
        if (is(head, "configs")) {
            if (s.length - i == 3 && is(s[i + 2], "extends")) {
                return new Key(profile, Section.EXTENDS, config(s[i + 1], origin), null, null, null);
            }
            if (s.length - i != 4) {
                throw new ProblemException(ProblemException.UNKNOWN_KEY, "incomplete key " + origin
                        + ": expected configs.<name>.<policy>.<attribute> or configs.<name>.extends");
            }
            String config = config(s[i + 1], origin);
            PolicyType<?, ?> policy = policy(s, i + 2, written, origin);
            return new Key(profile, Section.CONFIG, config, null, policy, attribute(policy, s, i + 3, written, origin));
        }
        if (is(head, "instances")) {
            return instance(profile, s, i, written, origin);
        }
        List<String> candidates = profile == null ? TOP_LEVEL : TOP_LEVEL.subList(0, 3);
        String closest = Suggestions.closest(head, candidates);
        throw new ProblemException(ProblemException.UNKNOWN_KEY, "unknown key " + origin + ": " + FORMS
                + (closest == null ? "" : ". Did you mean '" + replace(written, s, i, closest) + "'?"));
    }

    private static Key instance(@Nullable String profile, String[] s, int i, String written, String origin) {
        if (s.length - i > 4 && !s[i + 1].startsWith("\"") && PolicyType.find(s[s.length - 2]) != null) {
            // An unquoted derived id: com.acme.Api#get(java.lang.String) was split at its dots.
            String id = String.join(".", List.of(s).subList(i + 1, s.length - 2));
            String prefix = profile == null ? "" : "profiles." + profile + ".";
            throw new ProblemException(ProblemException.INVALID_INSTANCE_ID, "malformed key " + origin
                    + ": an instance id that contains '.' must be quoted. Did you mean '" + prefix + "instances.\""
                    + id + "\"." + s[s.length - 2] + "." + s[s.length - 1] + "'?");
        }
        if (s.length - i != 4) {
            throw new ProblemException(ProblemException.UNKNOWN_KEY, "incomplete key " + origin + ": expected "
                    + INSTANCE_FORM);
        }
        String id = s[i + 1];
        if (id.startsWith("\"")) {
            id = id.substring(1, id.length() - 1);
            if (id.isBlank()) {
                throw new ProblemException(ProblemException.INVALID_INSTANCE_ID, "malformed key " + origin
                        + ": the instance id is empty; " + INSTANCE_FORM);
            }
        }
        if (!id.equals(id.strip())) {
            throw new ProblemException(ProblemException.INVALID_INSTANCE_ID, "malformed key " + origin
                    + ": the instance id '" + id + "' starts or ends with whitespace");
        }
        PolicyType<?, ?> policy = policy(s, i + 2, written, origin);
        return new Key(profile, Section.INSTANCE, null, id, policy, attribute(policy, s, i + 3, written, origin));
    }

    /**
     * Splits a key at the dots that are not inside double quotes. A quoted segment keeps its quotes and
     * must be quoted as a whole.
     *
     * @throws ProblemException if a quote is not closed or does not delimit a whole segment
     */
    static String[] segments(String key, String origin) {
        if (key.indexOf('"') < 0) {
            return key.split("\\.", -1);
        }
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int j = 0; j < key.length(); j++) {
            char c = key.charAt(j);
            if (c == '"') {
                boolean opens = !quoted && current.isEmpty();
                boolean closes = quoted && (j + 1 == key.length() || key.charAt(j + 1) == '.');
                if (!opens && !closes) {
                    throw new ProblemException(ProblemException.INVALID_INSTANCE_ID, "malformed key " + origin
                            + ": a quoted segment must be quoted as a whole, between dots, and cannot contain '\"'; "
                            + INSTANCE_FORM);
                }
                quoted = opens;
                current.append(c);
            } else if (c == '.' && !quoted) {
                segments.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (quoted) {
            throw new ProblemException(ProblemException.INVALID_INSTANCE_ID, "malformed key " + origin
                    + ": unclosed '\"'; " + INSTANCE_FORM);
        }
        segments.add(current.toString());
        return segments.toArray(String[]::new);
    }

    private static boolean is(String segment, String keyword) {
        return Suggestions.normalize(segment).equals(keyword);
    }

    private static PolicyType<?, ?> policy(String[] s, int i, String written, String origin) {
        PolicyType<?, ?> policy = PolicyType.find(s[i]);
        if (policy != null) {
            return policy;
        }
        String hint;
        if (is(s[i], "fallback")) {
            hint = ". Fallbacks have no external configuration";
        } else {
            String closest = Suggestions.closest(s[i], PolicyType.names());
            hint = closest == null ? "" : ". Did you mean '" + replace(written, s, i, styled(closest, s[i])) + "'?";
        }
        throw new ProblemException(ProblemException.UNKNOWN_KEY, "unknown policy '" + s[i] + "' in key " + origin
                + hint + ". Policies: " + String.join(", ", PolicyType.names()));
    }

    private static String attribute(PolicyType<?, ?> policy, String[] s, int i, String written, String origin) {
        Attribute<?> attribute = policy.attribute(s[i]);
        if (attribute != null) {
            return attribute.name;
        }
        List<String> names = List.copyOf(policy.attributes.keySet());
        String closest = Suggestions.closest(s[i], names);
        throw new ProblemException(ProblemException.UNKNOWN_KEY, "unknown key " + origin
                + (closest == null ? "" : ". Did you mean '" + replace(written, s, i, styled(closest, s[i])) + "'?")
                + " Attributes of " + policy.name + ": " + String.join(", ", names));
    }

    /** Spells a canonical camelCase name in the style of what the user wrote. */
    private static String styled(String canonical, String written) {
        return written.indexOf('-') >= 0 ? Suggestions.kebab(canonical) : canonical;
    }

    /** The written key with segment {@code i} replaced (the written key may carry a prefix). */
    private static String replace(String written, String[] s, int i, String replacement) {
        List<String> parts = new ArrayList<>(List.of(s));
        parts.set(i, replacement);
        String rebuilt = String.join(".", parts);
        String key = String.join(".", s);
        return written.endsWith(key) ? written.substring(0, written.length() - key.length()) + rebuilt : rebuilt;
    }

    private static String config(String segment, String origin) {
        String config = name(segment, "config", origin);
        if (config.equals("default")) {
            throw new ProblemException(ProblemException.INVALID_NAME, "invalid key " + origin
                    + ": 'default' is reserved; set the defaults with defaults.<policy>.<attribute>");
        }
        return config;
    }

    /**
     * Validates a config or profile name.
     *
     * @throws ProblemException if the name is not lowercase letters and digits separated by single hyphens
     */
    static String name(String name, String what, String origin) {
        if (NAME.matcher(name).matches()) {
            return name;
        }
        String suggestion = Suggestions.kebab(name).replace('_', '-').toLowerCase(java.util.Locale.ROOT)
                .replaceAll("-+", "-").replaceAll("^-|-$", "");
        throw new ProblemException(ProblemException.INVALID_NAME, "invalid " + what + " name '" + name + "' in "
                + origin + ": names use lowercase letters and digits separated by single hyphens, so that they map"
                + " to environment variables"
                + (NAME.matcher(suggestion).matches() ? " (for example '" + suggestion + "')" : ""));
    }
}
