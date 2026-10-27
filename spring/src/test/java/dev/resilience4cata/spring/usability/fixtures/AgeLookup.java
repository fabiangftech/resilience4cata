package dev.resilience4cata.spring.usability.fixtures;

import org.springframework.stereotype.Component;

/** {@code Lookup<Integer>}. */
@Component
public class AgeLookup implements Lookup<Integer> {

    @Override
    public Integer find(String id) {
        return id.length();
    }
}
