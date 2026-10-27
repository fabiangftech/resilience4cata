package dev.resilience4cata.spring.usability.fixtures;

import org.springframework.stereotype.Component;

/** Injects one {@code Lookup} by its generic type, the usual way to pick among generic implementations. */
@Component
public class LookupConsumer {

    public final Lookup<String> names;

    public LookupConsumer(Lookup<String> names) {
        this.names = names;
    }
}
