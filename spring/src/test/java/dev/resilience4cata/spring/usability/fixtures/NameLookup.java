package dev.resilience4cata.spring.usability.fixtures;

import org.springframework.stereotype.Component;

/** {@code Lookup<String>}. */
@Component
public class NameLookup implements Lookup<String> {

    @Override
    public String find(String id) {
        return "name-" + id;
    }
}
