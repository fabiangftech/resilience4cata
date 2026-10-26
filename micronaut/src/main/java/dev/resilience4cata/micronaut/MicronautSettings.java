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
     *     {@code verify-instances} is {@code fail}, since every instance exists by then, otherwise {@code warn}
     */
    static MicronautSettings of(Environment environment, boolean eagerSingletons) {
        String mode = environment.getProperty(PREFIX + ".verify-instances", String.class)
                .orElse(eagerSingletons ? "fail" : "warn");
        VerifyInstances verify;
        try {
            verify = VerifyInstances.valueOf(mode.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid value '" + mode + "' for " + PREFIX
                    + ".verify-instances: use fail, warn or off.", e);
        }
        return new MicronautSettings(verify, list(environment, PREFIX + ".exclude"),
                list(environment, PREFIX + ".health.down-when-open"));
    }

    private static List<String> list(Environment environment, String key) {
        return environment.getProperty(key, Argument.listOf(String.class)).orElse(List.of()).stream()
                .map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
