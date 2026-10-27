package dev.resilience4cata.micronaut;

import io.micronaut.context.env.Environment;
import io.micronaut.core.type.Argument;
import java.util.List;
import java.util.Locale;

/**
 * The switches of the Micronaut integration itself, under {@code resilience4cata.micronaut}. Every other
 * {@code resilience4cata.*} key is policy configuration, read by {@code resilience4cata-config}.
 *
 * @param verifyInstances what to do at startup when a {@code resilience4cata.instances} key matches no policy
 *     instance yet
 * @param exclude fully qualified names of bean classes that the integration leaves alone (not checked)
 * @param downWhenOpen circuit breaker ids that turn the health indicator DOWN while open, or {@code *}
 */
record MicronautSettings(VerifyInstances verifyInstances, List<String> exclude, List<String> downWhenOpen) {

    /** The settings' prefix: {@value}. */
    static final String PREFIX = "resilience4cata.micronaut";

    MicronautSettings {
        exclude = List.copyOf(exclude);
        downWhenOpen = List.copyOf(downWhenOpen);
    }

    /** What to do with per-instance overrides that match no instance. */
    enum VerifyInstances {
        /** Fail the startup with {@code R4C-CFG-013} (the default when singletons are created eagerly). */
        FAIL,
        /** Log a warning with {@code R4C-CFG-013} (the default, unless singletons are created eagerly). */
        WARN,
        /** Do not check. */
        OFF
    }

    static MicronautSettings of(Environment environment) {
        return of(environment, false);
    }

    /**
     * @param eagerSingletons whether the context creates every singleton at startup: then the default of
     *     {@code verify-instances} is {@code fail}, otherwise {@code warn}; {@link InstanceVerifier} still uses
     *     {@code warn} when protected beans that are not singletons create their instances later
     */
    static MicronautSettings of(Environment environment, boolean eagerSingletons) {
        String mode = environment.getProperty(PREFIX + ".verify-instances", String.class)
                .orElse(eagerSingletons ? "fail" : "warn");
        VerifyInstances verify;
        try {
            verify = VerifyInstances.valueOf(mode.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ErrorCode.INVALID_SETTING.exception("Invalid value '" + mode + "' for " + PREFIX
                    + ".verify-instances.", "Use fail, warn or off.");
        }
        return new MicronautSettings(verify, list(environment, PREFIX + ".exclude"),
                breakerIds(list(environment, PREFIX + ".health.down-when-open")));
    }

    private static List<String> list(Environment environment, String key) {
        return environment.getProperty(key, Argument.listOf(String.class)).orElse(List.of()).stream()
                .map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    /**
     * The breaker ids of a list property: the framework splits a value at every comma, which also splits a derived id
     * such as {@code com.acme.Charges#charge(java.lang.String,int)}; parts are joined back while a parenthesis is open.
     */
    static List<String> breakerIds(List<String> parts) {
        List<String> ids = new java.util.ArrayList<>();
        StringBuilder pending = null;
        int open = 0;
        for (String part : parts) {
            pending = pending == null ? new StringBuilder(part.strip()) : pending.append(',').append(part.strip());
            open += (int) (part.chars().filter(c -> c == '(').count() - part.chars().filter(c -> c == ')').count());
            if (open <= 0) {
                ids.add(pending.toString());
                pending = null;
                open = 0;
            }
        }
        if (pending != null) {
            ids.add(pending.toString());
        }
        return ids.stream().filter(id -> !id.isEmpty()).toList();
    }
}
