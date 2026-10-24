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
 * profiles.&lt;profile&gt;.(any of the above)
 * profiles.active
 * </pre>
 *
 * @param profile the profile the key belongs to, or null
 * @param section what the key sets
 * @param config the named config, for {@link Section#CONFIG} and {@link Section#EXTENDS}
 * @param policy the policy, for {@link Section#DEFAULTS} and {@link Section#CONFIG}
 * @param attribute the canonical attribute name, for {@link Section#DEFAULTS} and {@link Section#CONFIG}
 */
record Key(@Nullable String profile, Section section, @Nullable String config, @Nullable PolicyType<?, ?> policy,
        @Nullable String attribute) {

    /** What a key sets. */
    enum Section {
        ACTIVE_PROFILES, DEFAULTS, CONFIG, EXTENDS
    }

    static final Pattern NAME = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final List<String> TOP_LEVEL = List.of("defaults", "configs", "profiles");
    private static final String FORMS = "expected defaults.<policy>.<attribute>, configs.<name>.<policy>.<attribute>,"
            + " configs.<name>.extends, profiles.<profile>.<one of those> or profiles.active";

    String canonical() {
        String prefix = profile == null ? "" : "profiles." + profile + ".";
        return switch (section) {
            case ACTIVE_PROFILES -> "profiles.active";
            case DEFAULTS -> prefix + "defaults." + policy + "." + attribute;
            case CONFIG -> prefix + "configs." + config + "." + policy + "." + attribute;
            case EXTENDS -> prefix + "configs." + config + ".extends";
        };
    }

    Key withoutProfile() {
        return new Key(null, section, config, policy, attribute);
    }

    /**
     * Parses a dotted key, without the optional {@code resilience4cata.}/{@code r4c.} prefix.
     *
     * @param written the key as written, for messages
     * @param origin where the key comes from, for messages
     * @throws ProblemException if the key is not valid
     */
    static Key parse(String key, String written, String origin) {
        String[] s = key.split("\\.", -1);
        for (String segment : s) {
            if (segment.isEmpty()) {
                throw new ProblemException(ProblemException.UNKNOWN_KEY, "malformed key " + origin
                        + ": empty segment; " + FORMS);
            }
        }
        String profile = null;
        int i = 0;
        if (is(s[0], "profiles")) {
            if (s.length == 2 && is(s[1], "active")) {
                return new Key(null, Section.ACTIVE_PROFILES, null, null, null);
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
            return new Key(profile, Section.DEFAULTS, null, policy, attribute(policy, s, i + 2, written, origin));
        }
        if (is(head, "configs")) {
            if (s.length - i == 3 && is(s[i + 2], "extends")) {
                return new Key(profile, Section.EXTENDS, config(s[i + 1], origin), null, null);
            }
            if (s.length - i != 4) {
                throw new ProblemException(ProblemException.UNKNOWN_KEY, "incomplete key " + origin
                        + ": expected configs.<name>.<policy>.<attribute> or configs.<name>.extends");
            }
            String config = config(s[i + 1], origin);
            PolicyType<?, ?> policy = policy(s, i + 2, written, origin);
            return new Key(profile, Section.CONFIG, config, policy, attribute(policy, s, i + 3, written, origin));
        }
        if (is(head, "instances")) {
            throw new ProblemException(ProblemException.NOT_SUPPORTED, "unsupported key " + origin
                    + ": per-instance overrides (instances.<id>.*) are not supported yet."
                    + " Use a named config (configs.<name>.*) and reference it with config = \"<name>\"");
        }
        List<String> candidates = profile == null ? TOP_LEVEL : TOP_LEVEL.subList(0, 2);
        String closest = Suggestions.closest(head, candidates);
        throw new ProblemException(ProblemException.UNKNOWN_KEY, "unknown key " + origin + ": " + FORMS
                + (closest == null ? "" : ". Did you mean '" + replace(written, s, i, closest) + "'?"));
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
